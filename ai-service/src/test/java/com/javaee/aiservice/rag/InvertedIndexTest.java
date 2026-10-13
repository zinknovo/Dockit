package com.javaee.aiservice.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 倒排索引 + BM25：分词行为（CJK 字符 bigram / 英文词）、真实 DF-IDF、增删与过滤。
 */
class InvertedIndexTest {

    private final InvertedIndex index = new InvertedIndex();

    @Test
    void cjkQueryMatchesDocumentsByCharacterBigrams() {
        index.index("d1", "旅行收纳袋，耐磨帆布", Map.of());
        index.index("d2", "桌面收纳盒，塑料材质", Map.of());
        index.index("d3", "无线蓝牙耳机", Map.of());

        List<Map<String, Object>> hits = index.search("耐磨", 10, Map.of());

        assertThat(hits).extracting(r -> r.get("id")).containsExactly("d1");
        assertThat(hits.get(0).get("similarity")).isInstanceOf(Number.class);
    }

    @Test
    void latinQueryMatchesCaseInsensitively() {
        index.index("d1", "Canvas travel pouch for trips", Map.of());
        index.index("d2", "塑料桌面收纳盒", Map.of());

        List<Map<String, Object>> hits = index.search("canvas", 10, Map.of());

        assertThat(hits).extracting(r -> r.get("id")).containsExactly("d1");
    }

    @Test
    void rareTermScoresAboveCommonTermWithEqualFrequency() {
        // 真实 DF-IDF 的行为锚点：词频相同（tf=1）时，只出现在 1 篇文档的"量子"
        // 必须比出现在全部 5 篇文档的"系统"得分更高（旧简化实现 IDF 是常数，二者相等）
        index.index("target", "量子 系统", Map.of());
        index.index("sys-1", "系统", Map.of());
        index.index("sys-2", "系统", Map.of());
        index.index("sys-3", "系统", Map.of());
        index.index("sys-4", "系统", Map.of());

        float rareScore = scoreOf(index.search("量子", 10, Map.of()), "target");
        float commonScore = scoreOf(index.search("系统", 10, Map.of()), "target");

        assertThat(rareScore).isGreaterThan(commonScore);
    }

    @Test
    void removedDocumentIsNoLongerSearchable() {
        index.index("d1", "耐磨帆布收纳袋", Map.of());
        index.index("d2", "塑料收纳盒", Map.of());

        index.remove("d1");

        assertThat(index.search("耐磨", 10, Map.of())).isEmpty();
        assertThat(index.search("收纳", 10, Map.of())).extracting(r -> r.get("id")).containsExactly("d2");
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void metadataFilterRestrictsSearchScope() {
        index.index("d1", "耐磨帆布收纳袋", Map.of("userId", "alice", "knowledgeBaseId", "kb-1"));
        index.index("d2", "耐磨塑料收纳盒", Map.of("userId", "bob", "knowledgeBaseId", "kb-2"));

        assertThat(index.search("耐磨", 10, Map.of("userId", "alice")))
                .extracting(r -> r.get("id")).containsExactly("d1");
        assertThat(index.search("耐磨", 10, Map.of("userId", "alice", "knowledgeBaseId", "kb-2")))
                .isEmpty();
    }

    @Test
    void loadFromUpsertsScrollPayloadsWithoutDroppingIndexedEntries() {
        index.index("stale", "旧内容", Map.of());

        index.loadFrom(List.of(
                Map.of("rawId", "s1", "type", "segment", "content", "合同违约条款"),
                Map.of("rawId", "s2", "type", "segment", "content", "会议纪要决议"),
                // 无 content 的点跳过
                Map.of("rawId", "s3", "type", "document")));

        assertThat(index.size()).isEqualTo(3);
        assertThat(index.search("违约", 10, Map.of())).extracting(r -> r.get("id")).containsExactly("s1");
        // 装载前已在索引里的条目不被清掉
        assertThat(index.search("旧内容", 10, Map.of())).extracting(r -> r.get("id")).containsExactly("stale");
    }

    private static float scoreOf(List<Map<String, Object>> hits, String id) {
        return hits.stream()
                .filter(h -> id.equals(h.get("id")))
                .findFirst()
                .map(h -> ((Number) h.get("similarity")).floatValue())
                .orElse(0f);
    }
}
