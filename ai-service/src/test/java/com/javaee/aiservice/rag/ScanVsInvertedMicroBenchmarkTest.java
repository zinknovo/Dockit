package com.javaee.aiservice.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 微基准（手工运行，默认跳过）：合成 1 万段语料，对比
 * A) 旧实现语义"全量扫描逐条打分"（复刻 da00b29:KnowledgeBase#computeBM25 及其调用循环）
 * B) 新实现"内存倒排索引查询"（{@link InvertedIndex#search}）
 * 的每查询延迟，验证 O(N) → O(posting) 的量级结论。
 * <p>
 * 语料设计：60 个领域各带独有词（长尾词汇分布），查询按选择性分档
 * （罕见词 ≈1.7% 触达 / 中频词 ≈50% / 高频词 ≈100%），分别观察倒排索引的收益区间。
 * <p>
 * 无外部依赖（合成语料 + 纯内存），运行：
 * {@code ./mvnw -pl ai-service test -Dtest=ScanVsInvertedMicroBenchmarkTest -Dmicrobench=true}
 * <p>
 * 注意：A 侧只计 CPU 打分成本；真实旧路径每查询还要经 Qdrant scrollAll 拉取全部
 * payload（网络 + JSON 反序列化），实际差距比本微基准更大。
 */
@EnabledIfSystemProperty(named = "microbench", matches = "true")
class ScanVsInvertedMicroBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(ScanVsInvertedMicroBenchmarkTest.class);

    /** 合成语料规模：万级分段（对应报告里 200 人公司 500~1500 篇 × 每篇多段） */
    private static final int DOC_COUNT = 10_000;
    private static final int TOP_K = 10;
    private static final int WARMUP_ROUNDS = 15;
    private static final int MEASURE_ROUNDS = 50;

    /** 60 个独有领域词（字符池两两组合，保证两两不互为子串） */
    private static final char[] MARKER_CHARS = "磐青云岭明湖澄海长风远山朗月映雪松涛星野".toCharArray();

    private static final String[] DOMAIN_MARKERS = buildMarkers();

    /** 每个领域的模板正文：领域词 + 通用的流程要求句，分布到 60 个领域 */
    private static String domainBody(int domain) {
        String marker = DOMAIN_MARKERS[domain];
        return marker + "专项说明：本文件围绕" + marker + "的立项背景、实施要求与验收标准展开。"
                + marker + "相关工作需明确责任人、时间节点与交付物，确保执行一致性。"
                + "任何一方违反约定的，应支付违约金并赔偿由此造成的损失。";
    }

    /** 通用句：高频词（所有文档都出现）与中频词（偶数号文档出现）各一组 */
    private static final String COMMON_SENTENCE = "本合同项下的违约责任、赔偿标准与争议解决方式适用相关法律与行业惯例；违约金按合同总额的百分之三计算。";
    private static final String MIDFREQ_SENTENCE = "保密义务：双方对履约过程中知悉的对方商业秘密负有保密义务，保密期限为合同终止后三年。";

    private static String[] buildMarkers() {
        List<String> markers = new ArrayList<>();
        for (char a : MARKER_CHARS) {
            for (char b : MARKER_CHARS) {
                if (a != b && markers.size() < 60) {
                    markers.add("" + a + b);
                }
            }
        }
        return markers.toArray(new String[0]);
    }

    @Test
    void scanVsInvertedOn10kSegments() {
        List<Map<String, Object>> payloads = synthesizeCorpus();

        InvertedIndex index = new InvertedIndex();
        long buildStart = System.nanoTime();
        index.loadFrom(payloads);
        double buildMs = (System.nanoTime() - buildStart) / 1e6;
        log.info("语料: {} 段 / {} 个独有领域词, 倒排索引构建 {} ms", DOC_COUNT, DOMAIN_MARKERS.length, Math.round(buildMs));

        // 查询按选择性分档：罕见词（单领域 ≈1.7%）/ 中频词（≈50%）/ 高频词（≈100%）
        String[][] queryGroups = {
                {"罕见词(≈1.7%)", DOMAIN_MARKERS[3], DOMAIN_MARKERS[27]},
                {"中频词(≈50%)", "保密期限"},
                {"高频词(≈100%)", "违约金", "赔偿责任"},
        };

        List<Double> scanLatencies = new ArrayList<>();
        List<Double> invertedLatencies = new ArrayList<>();
        List<Double> speedups = new ArrayList<>();

        // 触达规模统一先算：touchedDocs 重分配，混在计时区间之间会以 GC 形式污染后续测量
        Map<String, Long> touchedCounts = new HashMap<>();
        for (String[] group : queryGroups) {
            for (int q = 1; q < group.length; q++) {
                touchedCounts.put(group[q], touchedDocs(payloads, group[q]));
            }
        }

        for (String[] group : queryGroups) {
            String label = group[0];
            for (int q = 1; q < group.length; q++) {
                String query = group[q];

                for (int i = 0; i < WARMUP_ROUNDS; i++) {
                    scanAll(payloads, query, TOP_K);
                }
                long scanStart = System.nanoTime();
                for (int i = 0; i < MEASURE_ROUNDS; i++) {
                    scanAll(payloads, query, TOP_K);
                }
                double scanMs = (System.nanoTime() - scanStart) / MEASURE_ROUNDS / 1e6;
                scanLatencies.add(scanMs);

                for (int i = 0; i < WARMUP_ROUNDS; i++) {
                    index.search(query, TOP_K, Map.of());
                }
                long invStart = System.nanoTime();
                for (int i = 0; i < MEASURE_ROUNDS; i++) {
                    index.search(query, TOP_K, Map.of());
                }
                double invMs = (System.nanoTime() - invStart) / MEASURE_ROUNDS / 1e6;
                invertedLatencies.add(invMs);

                long touched = touchedCounts.get(query);
                speedups.add(scanMs / Math.max(invMs, 0.0001));
                log.info("查询[{}] {}: 扫描={} ms/次, 倒排={} ms/次, 加速={}x, 触达文档={}/{}",
                        label, query, fmt(scanMs), fmt(invMs), fmt(scanMs / Math.max(invMs, 0.0001)), touched, DOC_COUNT);
            }
        }

        log.info("--------- 汇总（{} 查询）---------", scanLatencies.size());
        log.info("旧实现 全量扫描打分: avg={} ms  p50={} ms  p95={} ms",
                fmt(avg(scanLatencies)), fmt(percentile(scanLatencies, 0.5)), fmt(percentile(scanLatencies, 0.95)));
        log.info("新实现 倒排索引查询: avg={} ms  p50={} ms  p95={} ms",
                fmt(avg(invertedLatencies)), fmt(percentile(invertedLatencies, 0.5)), fmt(percentile(invertedLatencies, 0.95)));
        log.info("加速比: min={}x max={}x（选择性越低收益越大；A 侧未计 Qdrant scrollAll 网络与反序列化成本，真实差距更大）",
                fmt(speedups.stream().mapToDouble(Double::doubleValue).min().orElse(0)),
                fmt(speedups.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
    }

    /** 合成语料：每段归属一个领域（领域词 + 领域正文 + 通用句），另按文档号注入中频句 */
    private static List<Map<String, Object>> synthesizeCorpus() {
        List<Map<String, Object>> payloads = new ArrayList<>(DOC_COUNT);
        for (int i = 0; i < DOC_COUNT; i++) {
            int domain = (i * 13) % DOMAIN_MARKERS.length; // 打散领域分布
            StringBuilder content = new StringBuilder();
            content.append("第1条 ").append(domainBody(domain));
            content.append(COMMON_SENTENCE);
            if (i % 2 == 0) {
                content.append(MIDFREQ_SENTENCE);
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("rawId", "synth-doc-" + i + "_seg_0");
            payload.put("type", "segment");
            payload.put("documentId", "synth-doc-" + i);
            payload.put("userId", "bench");
            payload.put("knowledgeBaseId", "default");
            payload.put("content", content.toString());
            payloads.add(payload);
        }
        return payloads;
    }

    /** 复刻旧实现：全量扫描 + computeBM25（da00b29:KnowledgeBase#bm25Search 循环体） */
    private static List<Map<String, Object>> scanAll(List<Map<String, Object>> payloads, String query, int topK) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> payload : payloads) {
            if (!"segment".equals(payload.get("type"))) {
                continue;
            }
            String content = payload.get("content") == null ? "" : payload.get("content").toString();
            if (content.isEmpty()) {
                continue;
            }
            float score = legacyComputeBm25(query, content);
            if (score > 0) {
                results.add(Map.of("id", payload.get("rawId"), "similarity", score));
            }
        }
        results.sort(Comparator.comparingDouble(
                (Map<String, Object> r) -> ((Number) r.get("similarity")).doubleValue()).reversed());
        return results.size() > topK ? results.subList(0, topK) : results;
    }

    /** 旧实现原文（da00b29:KnowledgeBase#computeBM25），按空格切词、逐条 contains——保留其成本形态 */
    private static float legacyComputeBm25(String query, String document) {
        if (query == null || document == null) {
            return 0.0f;
        }
        String[] queryTerms = query.toLowerCase().split("\\s+");
        String[] docTerms = document.toLowerCase().split("\\s+");
        int docLength = docTerms.length;
        if (docLength == 0) {
            return 0.0f;
        }
        float score = 0.0f;
        for (String term : queryTerms) {
            if (term.isEmpty()) {
                continue;
            }
            int termFreq = 0;
            for (String docTerm : docTerms) {
                if (docTerm.contains(term) || term.contains(docTerm)) {
                    termFreq++;
                }
            }
            if (termFreq > 0) {
                float tf = (float) termFreq / docLength;
                score += (float) (tf * (2.2 + 1) / (tf + 2.2));
            }
        }
        return score / queryTerms.length;
    }

    /** 统计包含任一查询词项的文档数（倒排查询的实际触达规模） */
    private static long touchedDocs(List<Map<String, Object>> payloads, String query) {
        List<String> queryTerms = InvertedIndex.tokenize(query);
        return payloads.stream()
                .filter(p -> {
                    List<String> terms = InvertedIndex.tokenize(p.get("content").toString());
                    return terms.stream().anyMatch(queryTerms::contains);
                })
                .count();
    }

    private static double avg(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double percentile(List<Double> values, double p) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int idx = (int) Math.ceil(sorted.size() * p) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
