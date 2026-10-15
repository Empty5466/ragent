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
 * 校园服务 MCP 工具：course_query 课表查询。
 * 返回当前学生今天或本周的上课安排，数据以学生姓名哈希为种子确定性生成。
 */
@Slf4j
@Component
public class CourseMcpExecutor {

    private static final String TOOL_ID = "course_query";

    private static final String DEFAULT_STUDENT = "小明";

    /** 每天最多 5 个大节，与教务系统时段一致 */
    private static final String[] PERIODS = {
            "08:00-09:35", "10:05-11:40", "14:00-15:35", "16:05-17:40", "19:00-20:35"
    };

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final List<String> COURSE_NAMES = List.of(
            "高等数学", "线性代数", "数据结构", "操作系统", "计算机网络",
            "数据库原理", "Java 程序设计", "大学英语", "软件工程", "体育");

    private static final List<String> ROOMS = List.of(
            "教一 201", "教三 401", "教三 502", "实验楼 305", "体育馆", "外语楼 108");

    private static final List<String> TEACHERS = List.of(
            "张老师", "李老师", "王老师", "刘老师", "陈老师");

    @Bean
    public McpServerFeatures.SyncToolSpecification courseToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .optional(string("studentName", "学生姓名或学号，不填则查询当前登录学生"))
                .optional(string("queryType", "查询范围：today(今天)、week(本周)")
                        .options(List.of("today", "week"))
                        .defaultTo("today"))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询学生的上课课表：可查今天有哪些课（时间段、教室、老师）或本周完整课表")
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
            if (StrUtil.isBlank(queryType)) queryType = "today";

            List<List<CourseSlot>> weekly = generateWeekly(studentName);

            String result = "week".equals(queryType)
                    ? buildWeekResult(studentName, weekly)
                    : buildTodayResult(studentName, weekly);

            log.info("MCP 工具调用完成, toolId={}, studentName={}, queryType={}, elapsed={}ms",
                    TOOL_ID, studentName, queryType, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("课表查询", e);
        }
    }

    private String buildTodayResult(String studentName, List<List<CourseSlot>> weekly) {
        LocalDate today = LocalDate.now();
        int dayIndex = today.getDayOfWeek().getValue() - 1; // 周一=0
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s %s（%s）课表】%n%n",
                studentName, today.format(DATE), weekdayName(today.getDayOfWeek().getValue())));

        if (dayIndex >= 5) {
            sb.append("今天是周末，没有课，好好休息。");
            return sb.toString().trim();
        }
        List<CourseSlot> slots = weekly.get(dayIndex);
        if (slots.isEmpty()) {
            sb.append("今天没有课。");
            return sb.toString().trim();
        }
        for (CourseSlot slot : slots) {
            sb.append(String.format("第 %d 大节 %s｜%s｜%s｜%s%n",
                    slot.period + 1, PERIODS[slot.period], slot.course, slot.room, slot.teacher));
        }
        return sb.toString().trim();
    }

    private String buildWeekResult(String studentName, List<List<CourseSlot>> weekly) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s 本周课表】%n", studentName));
        for (int d = 0; d < 5; d++) {
            sb.append(String.format("%n%s（周%d）%n", weekdayName(d + 1), d + 1));
            List<CourseSlot> slots = weekly.get(d);
            if (slots.isEmpty()) {
                sb.append("  无课%n");
                continue;
            }
            for (CourseSlot slot : slots) {
                sb.append(String.format("  第 %d 大节 %s｜%s｜%s｜%s%n",
                        slot.period + 1, PERIODS[slot.period], slot.course, slot.room, slot.teacher));
            }
        }
        return sb.toString().trim();
    }

    /** 每周 12-18 个大节，周一到周五分布，同名学生结果稳定 */
    private List<List<CourseSlot>> generateWeekly(String studentName) {
        Random random = new Random(studentName.hashCode() * 31L + "course".hashCode());
        List<List<CourseSlot>> weekly = new ArrayList<>();
        int total = 12 + random.nextInt(7);
        for (int d = 0; d < 5; d++) {
            weekly.add(new ArrayList<>());
        }
        while (total > 0) {
            int day = random.nextInt(5);
            int period = random.nextInt(PERIODS.length);
            List<CourseSlot> slots = weekly.get(day);
            boolean occupied = slots.stream().anyMatch(s -> s.period == period);
            if (occupied) continue;
            CourseSlot slot = new CourseSlot();
            slot.period = period;
            slot.course = COURSE_NAMES.get(random.nextInt(COURSE_NAMES.size()));
            slot.room = ROOMS.get(random.nextInt(ROOMS.size()));
            slot.teacher = TEACHERS.get(random.nextInt(TEACHERS.size()));
            slots.add(slot);
            total--;
        }
        for (List<CourseSlot> slots : weekly) {
            slots.sort((a, b) -> a.period - b.period);
        }
        return weekly;
    }

    private static String weekdayName(int isoValue) {
        return switch (isoValue) {
            case 1 -> "周一";
            case 2 -> "周二";
            case 3 -> "周三";
            case 4 -> "周四";
            case 5 -> "周五";
            case 6 -> "周六";
            default -> "周日";
        };
    }

    private static class CourseSlot {
        int period;
        String course;
        String room;
        String teacher;
    }
}
