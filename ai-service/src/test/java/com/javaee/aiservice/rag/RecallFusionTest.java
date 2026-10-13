package com.javaee.aiservice.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 召回层融合：RRF（倒数排名融合）与 UNION（向量通道优先、BM25 补位的旧语义）。
 */
class RecallFusionTest {

    @Test
    void rrfRanksDocumentsHitByBothChannelsAboveSingleChannelHits() {
        // d1 只在向量路第 1；d2 两路都命中（向量第 2、bm25 第 1）；d3 只在 bm25 第 2
        // 手算：d2 = 1/62 + 1/61 ≈ 0.03252 > d1 = 1/61 ≈ 0.01639 > d3 = 1/62 ≈ 0.01613
        List<Map<String, Object>> vector = List.of(hit("d1"), hit("d2"));
        List<Map<String, Object>> bm25 = List.of(hit("d2"), hit("d3"));

        List<Map<String, Object>> fused = RecallFusion.fuse(
                vector, bm25, 10, RecallFusion.Mode.RRF);

        assertThat(fused).extracting(r -> r.get("id")).containsExactly("d2", "d1", "d3");
        assertThat(fused.get(0).get("source")).isEqualTo("vector+bm25");
        assertThat(((Number) fused.get(0).get("similarity")).doubleValue())
                .isCloseTo(1.0 / 62 + 1.0 / 61, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void rrfBreaksScoreTiesByVectorChannelOrder() {
        // dA 只在向量第 1、dB 只在 bm25 第 1，RRF 分数同为 1/61；向量通道优先
        List<Map<String, Object>> vector = List.of(hit("dA"));
        List<Map<String, Object>> bm25 = List.of(hit("dB"));

        List<Map<String, Object>> fused = RecallFusion.fuse(
                vector, bm25, 10, RecallFusion.Mode.RRF);

        assertThat(fused).extracting(r -> r.get("id")).containsExactly("dA", "dB");
    }

    @Test
    void unionPreservesLegacyVectorFirstOrdering() {
        List<Map<String, Object>> vector = List.of(hit("d1"), hit("d2"));
        List<Map<String, Object>> bm25 = List.of(hit("d2"), hit("d3"));

        List<Map<String, Object>> fused = RecallFusion.fuse(
                vector, bm25, 10, RecallFusion.Mode.UNION);

        assertThat(fused).extracting(r -> r.get("id")).containsExactly("d1", "d2", "d3");
        assertThat(fused.get(0).get("source")).isEqualTo("vector");
        assertThat(fused.get(1).get("source")).isEqualTo("vector");
        assertThat(fused.get(2).get("source")).isEqualTo("bm25");
    }

    @Test
    void fusionTruncatesToTopK() {
        List<Map<String, Object>> vector = List.of(hit("d1"), hit("d2"), hit("d3"));
        List<Map<String, Object>> bm25 = List.of(hit("d4"));

        assertThat(RecallFusion.fuse(vector, bm25, 2, RecallFusion.Mode.RRF)).hasSize(2);
        assertThat(RecallFusion.fuse(vector, bm25, 2, RecallFusion.Mode.UNION)).hasSize(2);
    }

    private static Map<String, Object> hit(String id) {
        return Map.of("id", id, "similarity", 0.9f);
    }
}
