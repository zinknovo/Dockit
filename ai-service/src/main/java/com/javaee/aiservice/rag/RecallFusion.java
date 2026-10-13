package com.javaee.aiservice.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 召回层融合：把向量通道与 BM25 通道的候选合并为一份有序列表。
 * <p>
 * {@link Mode#RRF}：倒数排名融合，分数 = Σ 1/(K + rank)（K=60），两路都命中的候选
 * 累加两份贡献；分数尺度无关，不需要归一化。同分时向量通道名次优先。
 * {@link Mode#UNION}：旧语义，向量通道原序在前，BM25 独有候选按名次补位，去重截断。
 */
public final class RecallFusion {

    public enum Mode {UNION, RRF}

    /** RRF 平滑常数，标准取值 60 */
    static final int RRF_K = 60;

    private RecallFusion() {
    }

    public static List<Map<String, Object>> fuse(List<Map<String, Object>> vectorResults,
                                                 List<Map<String, Object>> bm25Results,
                                                 int topK,
                                                 Mode mode) {
        return mode == Mode.RRF
                ? fuseByRrf(vectorResults, bm25Results, topK)
                : fuseByUnion(vectorResults, bm25Results, topK);
    }

    private static List<Map<String, Object>> fuseByRrf(List<Map<String, Object>> vectorResults,
                                                       List<Map<String, Object>> bm25Results,
                                                       int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Map<String, Object>> entries = new HashMap<>();
        Map<String, String> channels = new HashMap<>();
        List<String> order = new ArrayList<>();

        accumulate(vectorResults, "vector", scores, entries, channels, order);
        accumulate(bm25Results, "bm25", scores, entries, channels, order);

        return order.stream()
                .sorted((a, b) -> {
                    int byScore = Double.compare(scores.get(b), scores.get(a));
                    return byScore != 0 ? byScore : Integer.compare(order.indexOf(a), order.indexOf(b));
                })
                .limit(Math.max(topK, 0))
                .map(id -> {
                    Map<String, Object> fused = new HashMap<>(entries.get(id));
                    fused.put("similarity", scores.get(id));
                    fused.put("source", channels.get(id));
                    return fused;
                })
                .toList();
    }

    private static void accumulate(List<Map<String, Object>> results,
                                   String channel,
                                   Map<String, Double> scores,
                                   Map<String, Map<String, Object>> entries,
                                   Map<String, String> channels,
                                   List<String> order) {
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> result = results.get(i);
            String id = String.valueOf(result.get("id"));
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            entries.putIfAbsent(id, result);
            channels.merge(id, channel, (a, b) -> "vector+bm25");
            if (!order.contains(id)) {
                order.add(id);
            }
        }
    }

    private static List<Map<String, Object>> fuseByUnion(List<Map<String, Object>> vectorResults,
                                                         List<Map<String, Object>> bm25Results,
                                                         int topK) {
        List<Map<String, Object>> combined = new ArrayList<>();
        for (Map<String, Object> result : vectorResults) {
            Map<String, Object> copy = new HashMap<>(result);
            copy.put("source", "vector");
            combined.add(copy);
        }
        for (Map<String, Object> result : bm25Results) {
            String id = String.valueOf(result.get("id"));
            boolean seen = combined.stream().anyMatch(r -> String.valueOf(r.get("id")).equals(id));
            if (!seen) {
                Map<String, Object> copy = new HashMap<>(result);
                copy.put("source", "bm25");
                combined.add(copy);
            }
        }
        return combined.subList(0, Math.min(Math.max(topK, 0), combined.size()));
    }
}
