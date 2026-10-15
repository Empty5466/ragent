#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ragent 检索层评测（向量召回命中率，零依赖）

原理: 问题 → 百炼 text-embedding-v4 向量化 → pgvector 余弦 Top-5 →
      检查预期来源文档是否命中（metadata->>'source_file'）
指标: Recall@1 / Recall@5（检索命中率），这是简历"召回命中率"的直接来源

用法: python run_retrieval_eval.py
前置: BAILIAN_API_KEY 环境变量；ragent-pg 容器运行中
"""

import json
import os
import subprocess
import urllib.request
from datetime import datetime
from pathlib import Path

EMBED_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"
EMBED_MODEL = "text-embedding-v4"
DIMENSION = 1536
COLLECTION = "school"
TOP_K = 5


def embed(text, api_key):
    body = json.dumps({
        "model": EMBED_MODEL,
        "input": [text],
        "dimensions": DIMENSION,
        "encoding_format": "float",
    }).encode("utf-8")
    req = urllib.request.Request(
        EMBED_URL, data=body,
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {api_key}"},
        method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["data"][0]["embedding"]


def top5_sources(vector):
    vec = "[" + ",".join(f"{x:.6f}" for x in vector) + "]"
    sql = (
        "SELECT v.metadata->>'source_file' "
        "FROM t_knowledge_vector v "
        f"WHERE v.collection_name = '{COLLECTION}' "
        f"ORDER BY v.embedding <=> '{vec}'::vector LIMIT {TOP_K}"
    )
    out = subprocess.run(
        ["docker", "exec", "ragent-pg", "psql", "-U", "postgres", "-d", "ragent",
         "-t", "-A", "-c", sql],
        capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        raise RuntimeError(f"SQL 失败: {out.stderr[:300]}")
    return [line.strip() for line in out.stdout.splitlines() if line.strip()]


def main():
    api_key = os.environ.get("BAILIAN_API_KEY")
    if not api_key:
        raise SystemExit("环境变量 BAILIAN_API_KEY 未设置")

    cases = [c for c in json.loads(
        (Path(__file__).parent / "testset.json").read_text(encoding="utf-8"))["cases"]
        if c["type"] == "kb"]

    # 集合内向量自检
    out = subprocess.run(
        ["docker", "exec", "ragent-pg", "psql", "-U", "postgres", "-d", "ragent", "-t", "-A", "-c",
         f"SELECT count(*) FROM t_knowledge_vector WHERE collection_name='{COLLECTION}'"],
        capture_output=True, text=True, timeout=15)
    total = out.stdout.strip()
    print(f"集合 {COLLECTION} 共 {total} 个向量，评测 {len(cases)} 题\n")
    if total == "0":
        raise SystemExit("集合为空，请先上传文档并分块")

    results, hit1, hit5 = [], 0, 0
    for i, c in enumerate(cases, 1):
        vec = embed(c["question"], api_key)
        sources = top5_sources(vec)
        expected = c["expected_doc"] + ".md"
        rank = sources.index(expected) + 1 if expected in sources else None
        hit1 += rank == 1
        hit5 += rank is not None
        flag = f"TOP{rank}" if rank else "MISS"
        print(f"[{i:>2}/{len(cases)}] {flag:<5} {c['id']:<10} {c['question']}")
        if not rank:
            print(f"      └─ Top5: {sources}")
        results.append({"id": c["id"], "question": c["question"], "expected": expected,
                        "rank": rank, "top5": sources})
        import time
        time.sleep(0.3)

    n = len(cases)
    print("\n===== 检索层汇总 =====")
    print(f"  Recall@1  {hit1}/{n} = {round(hit1 / n * 100, 1)}%")
    print(f"  Recall@{TOP_K}  {hit5}/{n} = {round(hit5 / n * 100, 1)}%")

    report = Path(__file__).parent / f"retrieval-report-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
    report.write_text(json.dumps({
        "collection": COLLECTION, "top_k": TOP_K, "model": EMBED_MODEL,
        "recall_at_1": round(hit1 / n * 100, 1),
        "recall_at_5": round(hit5 / n * 100, 1),
        "cases": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"报告已保存: {report}")


if __name__ == "__main__":
    main()
