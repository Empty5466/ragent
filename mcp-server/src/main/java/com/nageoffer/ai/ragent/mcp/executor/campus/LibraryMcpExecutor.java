/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.executor.campus;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 校园服务 MCP 工具：library_query 图书借阅查询。
 * 返回当前学生在借图书清单与历史借阅记录，数据与《图书馆借阅规则》知识库文档保持一致
 * （本科生可借 10 册，借期 30 天，续借最多 5 次、每次 10 天，超期按梯度停借）。
 * mock 数据以学生姓名哈希为种子确定性生成：同名同日查询结果稳定，无需真实存储。
 */
@Slf4j
@Component
public class LibraryMcpExecutor {

    private static final String TOOL_ID = "library_query";

    private static final String DEFAULT_STUDENT = "小明";

    /** 与知识库文档《图书馆借阅规则》保持一致的规则常量 */
    private static final int MAX_BORROW = 10;
    private static final int BORROW_DAYS = 30;
    private static final int MAX_RENEW = 5;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final List<String> BOOK_TITLES = List.of(
            "深入理解计算机系统", "Java 核心技术 卷 I", "算法导论", "高性能 MySQL",
            "计算机网络：自顶向下方法", "操作系统导论", "机器学习（西瓜书）", "数据结构与算法分析");

    @Bean
    public McpServerFeatures.SyncToolSpecification libraryToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .optional(string("studentName", "学生姓名或学号，不填则查询当前登录学生"))
                .optional(string("queryType", "查询类型：borrowed(在借清单)、history(历史借阅)")
                        .options(List.of("borrowed", "history"))
                        .defaultTo("borrowed"))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询学生的图书借阅信息：在借图书清单（含应还日期、已续借次数、是否超期）"
                        + "或历史借阅记录，并给出剩余可借册数")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            var args = McpToolResults.args(request);
            String studentName = MapUtil.getStr(args, "studentName");
            String queryType = MapUtil.getStr(args, "queryType");

            if (StrUtil.isBlank(studentName)) studentName = DEFAULT_STUDENT;
            if (StrUtil.isBlank(queryType)) queryType = "borrowed";

            List<BorrowRecord> records = generateRecords(studentName);

            String result = "history".equals(queryType)
                    ? buildHistoryResult(studentName, records)
                    : buildBorrowedResult(studentName, records);

            log.info("MCP 工具调用完成, toolId={}, studentName={}, queryType={}, elapsed={}ms",
                    TOOL_ID, studentName, queryType, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("借阅查询", e);
        }
    }

    private String buildBorrowedResult(String studentName, List<BorrowRecord> all) {
        String today = LocalDate.now().format(DATE);
        List<BorrowRecord> borrowed = all.stream().filter(r -> !r.returned).toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s 在借图书】共 %d 册%n%n", studentName, borrowed.size()));
        for (int i = 0; i < borrowed.size(); i++) {
            BorrowRecord r = borrowed.get(i);
            String status = r.dueDate.compareTo(today) < 0
                    ? String.format("已超期 %d 天（超期 1-7 天停借 7 天，8-14 天停借 30 天）",
                            LocalDate.parse(today).toEpochDay() - LocalDate.parse(r.dueDate).toEpochDay())
                    : "正常";
            sb.append(String.format("%d. 《%s》%n   借出: %s，应还: %s，已续借 %d/%d 次，状态: %s%n",
                    i + 1, r.title, r.borrowDate, r.dueDate, r.renewTimes, MAX_RENEW, status));
        }
        sb.append(String.format("%n剩余可借: %d 册（本科生上限 %d 册，借期 %d 天）",
                MAX_BORROW - borrowed.size(), MAX_BORROW, BORROW_DAYS));

        BorrowRecord nearest = borrowed.stream()
                .filter(r -> r.dueDate.compareTo(today) >= 0)
                .min((a, b) -> a.dueDate.compareTo(b.dueDate))
                .orElse(null);
        if (nearest != null) {
            sb.append(String.format("%n最近应还: 《%s》%s 前归还，到期前可续借", nearest.title, nearest.dueDate));
        }
        return sb.toString().trim();
    }

    private String buildHistoryResult(String studentName, List<BorrowRecord> all) {
        List<BorrowRecord> returned = all.stream().filter(BorrowRecord::isReturned).toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s 历史借阅】共 %d 条%n%n", studentName, returned.size()));
        if (returned.isEmpty()) {
            sb.append("暂无历史借阅记录");
            return sb.toString().trim();
        }
        for (int i = 0; i < returned.size(); i++) {
            BorrowRecord r = returned.get(i);
            String onTime = r.returnDate.compareTo(r.dueDate) <= 0 ? "按时归还" : "超期归还";
            sb.append(String.format("%d. 《%s》%s 借出 → %s 归还（%s）%n",
                    i + 1, r.title, r.borrowDate, r.returnDate, onTime));
        }
        return sb.toString().trim();
    }

    private List<BorrowRecord> generateRecords(String studentName) {
        Random random = new Random(studentName.hashCode() * 31L + "library".hashCode());
        List<BorrowRecord> records = new ArrayList<>();
        LocalDate today = LocalDate.now();

        // 在借 2-4 册，其中约 1/3 概率出现一本超期书
        int borrowing = 2 + random.nextInt(3);
        for (int i = 0; i < borrowing; i++) {
            BorrowRecord r = new BorrowRecord();
            r.title = BOOK_TITLES.get(random.nextInt(BOOK_TITLES.size()));
            boolean overdue = i == 0 && random.nextInt(3) == 0;
            r.borrowDate = today.minusDays(overdue ? BORROW_DAYS + 1L + random.nextInt(10) : random.nextInt(BORROW_DAYS - 5))
                    .format(DATE);
            r.dueDate = LocalDate.parse(r.borrowDate).plusDays(BORROW_DAYS).format(DATE);
            r.renewTimes = random.nextInt(4);
            r.returned = false;
            records.add(r);
        }

        // 历史 3-5 条已归还记录
        int history = 3 + random.nextInt(3);
        for (int i = 0; i < history; i++) {
            BorrowRecord r = new BorrowRecord();
            r.title = BOOK_TITLES.get(random.nextInt(BOOK_TITLES.size()));
            LocalDate borrow = today.minusDays(60L + random.nextInt(120));
            r.borrowDate = borrow.format(DATE);
            r.dueDate = borrow.plusDays(BORROW_DAYS).format(DATE);
            r.renewTimes = random.nextInt(MAX_RENEW + 1);
            r.returned = true;
            r.returnDate = borrow.plusDays(BORROW_DAYS - 5L + random.nextInt(15)).format(DATE);
            records.add(r);
        }
        return records;
    }

    private static class BorrowRecord {
        String title;
        String borrowDate;
        String dueDate;
        int renewTimes;
        boolean returned;
        String returnDate;

        boolean isReturned() {
            return returned;
        }
    }
}
