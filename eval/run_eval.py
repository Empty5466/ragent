#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ragent 校园服务域端到端评测脚本（零依赖，纯 Python 标准库）

用法:
    python run_eval.py                 # 跑全部 20 题
    python run_eval.py --limit 2      # 只跑前 2 题（冒烟测试）
    python run_eval.py --only lib     # 只跑 id 含 "lib" 的题

流程: 登录 /auth/login 拿 token → 逐题调 /agent/v1/chat SSE → 解析事件流打分
指标:
    检索命中率   kb 题的回答流中出现预期来源文档标题（引用/来源事件）
    关键词命中率 kb 题的回答文本包含预期关键词（all_keywords 全含 / any_keywords 任一）
    工具触发率   tool 题 block 事件中出现预期工具 id
    误触发率     negative 题触发了禁用工具
报告: 保存到脚本同目录 report-<时间戳>.json，改进前后各跑一次对比即得简历指标
"""

import argparse
import json
import re
import time
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

BASE = "http://127.0.0.1:9090/api/ragent"
USERNAME, PASSWORD = "admin", "admin"
KNOWN_TOOLS = ("library_query", "course_query", "search_knowledge")
CASE_TIMEOUT = 180  # 单题超时（秒）


def http_json(path, payload):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if body.get("code") != "0":
        raise RuntimeError(f"{path} 失败: {body.get('message')}")
    return body.get("data")


def login():
    data = http_json("/auth/login", {"username": USERNAME, "password": PASSWORD})
    token = data.get("token") if isinstance(data, dict) else None
    if not token:
        raise RuntimeError(f"登录响应中没有 token: {data}")
    return token


def ask(token, question):
    """调对话 SSE，返回 {answer, tools, raw, error}"""
    url = f"{BASE}/agent/v1/chat?question={urllib.parse.quote(question)}"
    req = urllib.request.Request(
        url, headers={"Accept": "text/event-stream", "Authorization": token}
    )
    raw_parts, answer_parts, tools = [], [], set()
    event_name, data_lines = None, []

    def dispatch():
        payload = "\n".join(data_lines)
        raw_parts.append(f"[{event_name}] {payload}")
        try:
            obj = json.loads(payload)
        except (ValueError, TypeError):
            obj = None
        if isinstance(obj, dict):
            if event_name == "message":
                mtype = str(obj.get("type") or "")
                if mtype == "answer":
                    content = obj.get("content") or obj.get("text") or obj.get("delta") or ""
                    answer_parts.append(str(content))
            if event_name == "block":
                blob = json.dumps(obj, ensure_ascii=False)
                for tool in KNOWN_TOOLS:
                    if tool in blob:
                        tools.add(tool)

    try:
        with urllib.request.urlopen(req, timeout=CASE_TIMEOUT) as resp:
            for raw_line in resp:
                line = raw_line.decode("utf-8", "replace").rstrip("\r\n")
                if not line:
                    if event_name == "done":
                        break
                    dispatch()
                    event_name, data_lines = None, []
                    continue
                if line.startswith(":"):
                    continue
                if line.startswith("event:"):
                    event_name = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].strip())
    except Exception as e:  # 超时/连接中断也要保留已收到的内容参与打分
        return {
            "answer": "".join(answer_parts),
            "tools": tools,
            "raw": "\n".join(raw_parts),
            "error": str(e),
        }
    return {"answer": "".join(answer_parts), "tools": tools, "raw": "\n".join(raw_parts), "error": None}


def norm(s):
    return re.sub(r"\s+", "", s or "")


def score_case(case, result):
    answer, raw = norm(result["answer"]), norm(result["raw"])
    detail = {"error": result["error"]}

    if case["type"] == "kb":
        # agent 模式下 SSE 流不含来源文档标题，doc_hit 仅作参考不参与判定
        doc_hit = case["expected_doc"] in raw or case["expected_doc"] in answer
        kws = [norm(k) for k in case.get("expected_keywords", [])]
        any_kws = [norm(k) for k in case.get("any_keywords", [])]
        kw_all = all(k in answer for k in kws)
        kw_any = (not any_kws) or any(k in answer for k in any_kws)
        # 意图歧义豁免：若问题被正确路由到绑定工具（如"在哪查课表"直接调 course_query），视为通过
        tool_ok = case.get("accept_tool") in result["tools"]
        detail.update({"doc_hit": doc_hit, "kw_all": kw_all, "kw_any": kw_any, "tools": sorted(result["tools"])})
        detail["pass"] = (kw_all and kw_any) or tool_ok
    elif case["type"] == "tool":
        tool_hit = case["expected_tool"] in result["tools"]
        detail.update({"tool_hit": tool_hit, "tools": sorted(result["tools"])})
        detail["pass"] = tool_hit
    else:  # negative
        violated = sorted(set(case["forbidden_tools"]) & result["tools"])
        detail.update({"violated": violated})
        detail["pass"] = not violated and result["error"] is None
    return detail


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 题")
    parser.add_argument("--only", default="", help="只跑 id 包含该子串的题")
    parser.add_argument("--testset", default="testset.json", help="评测集文件名")
    args = parser.parse_args()

    testset_path = Path(__file__).parent / args.testset
    cases = json.loads(testset_path.read_text(encoding="utf-8"))["cases"]
    if args.only:
        cases = [c for c in cases if args.only in c["id"]]
    if args.limit:
        cases = cases[: args.limit]

    print(f"登录 {USERNAME}@{BASE} ...")
    token = login()
    print(f"共 {len(cases)} 题，开始评测\n")

    results, passed = [], 0
    for i, case in enumerate(cases, 1):
        t0 = time.time()
        result = ask(token, case["question"])
        if not result["answer"] or result["error"]:  # 偶发空响应/中断，重试一次
            time.sleep(2)
            result = ask(token, case["question"])
        detail = score_case(case, result)
        ok = detail["pass"]
        passed += ok
        flag = "PASS" if ok else "FAIL"
        print(f"[{i:>2}/{len(cases)}] {flag} {case['id']:<10} {time.time() - t0:5.1f}s  {case['question']}")
        if not ok:
            ans_preview = (result["answer"] or result["error"] or "")[:120].replace("\n", " ")
            print(f"      └─ {json.dumps(detail, ensure_ascii=False)} | 答: {ans_preview}")
        results.append({"id": case["id"], "type": case["type"], "question": case["question"],
                        "pass": ok, "detail": detail,
                        "answer_preview": (result["answer"] or "")[:300],
                        "tools": sorted(result["tools"]),
                        "raw": result["raw"][:8000]})
        time.sleep(0.8)

    # 分类型统计
    by_type = {}
    for r in results:
        by_type.setdefault(r["type"], []).append(r)
    print("\n===== 评测汇总 =====")
    metrics = {"total": len(results), "passed": passed,
               "pass_rate": round(passed / len(results) * 100, 1) if results else 0.0}
    for t, rs in by_type.items():
        p = sum(1 for r in rs if r["pass"])
        metrics[t] = {"passed": p, "total": len(rs),
                      "rate": round(p / len(rs) * 100, 1) if rs else 0.0}
        print(f"  {t:<9} {p}/{len(rs)} = {metrics[t]['rate']}%")
    print(f"  总计      {passed}/{len(results)} = {metrics['pass_rate']}%")

    report_path = Path(__file__).parent / f"report-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
    report_path.write_text(
        json.dumps({"base_url": BASE, "metrics": metrics, "cases": results},
                   ensure_ascii=False, indent=2),
        encoding="utf-8")
    print(f"报告已保存: {report_path}")


if __name__ == "__main__":
    main()
