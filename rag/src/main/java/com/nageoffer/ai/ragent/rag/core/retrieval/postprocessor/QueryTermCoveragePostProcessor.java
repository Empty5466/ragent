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

package com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询词覆盖度加权后置处理器（自研，order=7）
 * <p>
 * 插在 Fusion(5) 之后、Rerank(10) 之前：
 * RRF 融合只看通道内名次，不感知"片段是否真的包含查询词"；
 * 而 Rerank 只对 Top-N 候选精排——如果包含查询词的片段没挤进候选窗口，
 * 后续精排再准也救不回来。
 * <p>
 * 本处理器按查询词覆盖度对片段做小幅加分（只加不减、不改变相对顺序的稳定性），
 * 让"包含用户原词"的片段更大概率进入精排候选窗口；
 * 同时在精排降级/跳过时，作为关键词信号的兜底排序依据。
 * <p>
 * 词项提取采用零依赖方案：中文按 2-gram 切分、英文/数字按连续串切分，
 * 短查询与长查询统一归一化为覆盖率，避免长查询天然低覆盖被惩罚。
 */
@Slf4j
@Component
public class QueryTermCoveragePostProcessor implements SearchResultPostProcessor {

    /**
     * 加权上限：RRF 得分量级约 0.03~0.10，加分超过该量级会颠倒名次语义
     */
    private static final float MAX_BOOST = 0.05f;

    @Override
    public String getName() {
        return "QueryTermCoverage";
    }

    @Override
    public int getOrder() {
        return 7;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return context.getRewrittenQuestion() != null && !context.getRewrittenQuestion().isBlank();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        Set<String> terms = extractTerms(context.getRewrittenQuestion());
        if (terms.isEmpty() || chunks == null || chunks.isEmpty()) {
            return chunks;
        }
        int boosted = 0;
        for (RetrievedChunk chunk : chunks) {
            String text = chunk.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            int hits = 0;
            for (String term : terms) {
                if (text.contains(term)) {
                    hits++;
                }
            }
            float coverage = (float) hits / terms.size();
            if (coverage > 0f && chunk.getScore() != null && Float.isFinite(chunk.getScore())) {
                chunk.setScore(chunk.getScore() + MAX_BOOST * coverage);
                boosted++;
            }
        }
        log.info("[QueryTermCoverage] terms={} boosted={}/{}", terms.size(), boosted, chunks.size());
        return chunks;
    }

    /**
     * 零依赖词项提取：中文 2-gram + 英文/数字连续串（长度 >= 2）
     * 2-gram 粒度粗但对"包含判断"足够——目标是覆盖度信号而非分词精度
     */
    private Set<String> extractTerms(String question) {
        Set<String> terms = new HashSet<>();
        if (question == null || question.isBlank()) {
            return terms;
        }
        char[] chars = question.toCharArray();
        StringBuilder asciiBuf = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c >= 0x4E00 && c <= 0x9FFF) { // CJK 统一表意文字
                asciiBuf.setLength(0);
                if (i + 1 < chars.length && chars[i + 1] >= 0x4E00 && chars[i + 1] <= 0x9FFF) {
                    terms.add(question.substring(i, i + 2));
                }
            } else if (Character.isLetterOrDigit(c)) {
                asciiBuf.append(c);
            } else {
                flushAscii(asciiBuf, terms);
            }
        }
        flushAscii(asciiBuf, terms);
        return terms;
    }

    private void flushAscii(StringBuilder buf, Set<String> terms) {
        if (buf.length() >= 2) {
            terms.add(buf.toString().toLowerCase());
        }
        buf.setLength(0);
    }
}
