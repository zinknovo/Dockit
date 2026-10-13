package com.javaee.aiservice.rag;

import com.javaee.aiservice.aiops.MonitoringService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归：文档内容从分段点按序拼接（此前直接读不存在的文档级点，恒返回 null）；
 * 索引中途失败时回滚已写入的分段点（此前残留孤儿向量）。
 */
class KnowledgeBaseTest {

    private final DocumentVectorizer vectorizer = mock(DocumentVectorizer.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final Reranker reranker = mock(Reranker.class);
    private final DocumentSegmenter segmenter = mock(DocumentSegmenter.class);
    private final MonitoringService monitoringService = mock(MonitoringService.class);

    private KnowledgeBase knowledgeBase(String fusionMode) {
        return new KnowledgeBase(vectorizer, vectorStore, reranker, segmenter, monitoringService,
                new InvertedIndex(), fusionMode);
    }

    @Test
    void getDocumentContentConcatenatesSegmentsInOrder() {
        KnowledgeBase kb = knowledgeBase("UNION");
        when(vectorStore.scrollAll(anyMap())).thenReturn(List.of(
                point("doc-1_seg_0", 0.0, "片段A"),
                point("doc-1_seg_1", 1.0, "片段B")));
        when(vectorStore.getPayload("doc-1_seg_0")).thenReturn(Map.of("content", "片段A"));
        when(vectorStore.getPayload("doc-1_seg_1")).thenReturn(Map.of("content", "片段B"));

        assertThat(kb.getDocumentContent("doc-1")).isEqualTo("片段A\n\n片段B");
    }

    @Test
    void getDocumentContentFallsBackToWholeDocumentPoint() {
        KnowledgeBase kb = knowledgeBase("UNION");
        when(vectorStore.scrollAll(anyMap())).thenReturn(List.of());
        when(vectorStore.getPayload("doc-1")).thenReturn(Map.of("content", "整篇内容"));

        assertThat(kb.getDocumentContent("doc-1")).isEqualTo("整篇内容");
    }

    @Test
    void addDocumentRollsBackStoredSegmentsOnFailure() {
        KnowledgeBase kb = knowledgeBase("UNION");
        when(segmenter.segment(eq("doc-1"), anyString(), any())).thenReturn(List.of(
                new SegmentStrategy.Segment("doc-1_seg_0", "doc-1", "片段A", 0, null),
                new SegmentStrategy.Segment("doc-1_seg_1", "doc-1", "片段B", 1, null)));
        when(vectorizer.vectorizeBatch(any(String[].class)))
                .thenReturn(new float[][]{{1f}})
                .thenThrow(new RuntimeException("embedding 500"));

        assertThatThrownBy(() -> kb.addDocumentWithSegment("doc-1", "内容", Map.of(),
                DocumentSegmenter.StrategyType.FIXED_LENGTH))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("添加文档失败");

        verify(vectorStore).delete("doc-1_seg_0");
        verify(vectorStore, never()).delete("doc-1_seg_1");
    }

    private static Map<String, Object> point(String rawId, double segmentIndex, String content) {
        Map<String, Object> p = new HashMap<>();
        p.put("rawId", rawId);
        p.put("segmentIndex", segmentIndex);
        p.put("type", "segment");
        p.put("documentId", "doc-1");
        p.put("content", content);
        return p;
    }

    private static Map<String, Object> segmentPayload(String rawId, String content) {
        Map<String, Object> p = new HashMap<>();
        p.put("rawId", rawId);
        p.put("type", "segment");
        p.put("documentId", "doc-bm25");
        p.put("content", content);
        return p;
    }

    @Test
    void hybridSearchScoresKeywordChannelThroughInvertedIndexAndScansStoreOnlyOnce() {
        KnowledgeBase kb = knowledgeBase("UNION");
        when(vectorizer.vectorize(anyString())).thenReturn(new float[]{1f});
        when(vectorStore.search(any(float[].class), anyInt(), anyMap())).thenReturn(List.of());
        // 首次装载的来源：Qdrant 里的存量分段
        when(vectorStore.scrollAll()).thenReturn(List.of(
                segmentPayload("kw-doc_seg_0", "违约责任条款与赔偿规定")));
        when(vectorStore.getPayload("kw-doc_seg_0")).thenReturn(Map.of("content", "违约责任条款与赔偿规定"));

        // 第一次查询：触发一次性装载，之后查询不再全量扫描
        List<Map<String, Object>> first = kb.hybridSearch("违约责任", 5,
                DocumentSegmenter.StrategyType.FIXED_LENGTH, Map.of());
        List<Map<String, Object>> second = kb.hybridSearch("违约责任", 5,
                DocumentSegmenter.StrategyType.FIXED_LENGTH, Map.of());

        assertThat(first).extracting(r -> r.get("id")).containsExactly("kw-doc_seg_0");
        assertThat(first.get(0).get("source")).isEqualTo("bm25");
        assertThat(first.get(0).get("content")).isEqualTo("违约责任条款与赔偿规定");
        assertThat(second).extracting(r -> r.get("id")).containsExactly("kw-doc_seg_0");
        verify(vectorStore, times(1)).scrollAll();
    }

    @Test
    void documentsAddedThroughApiAreSearchableThroughKeywordChannel() {
        KnowledgeBase kb = knowledgeBase("UNION");
        when(segmenter.segment(eq("doc-bm25"), anyString(), any()))
                .thenReturn(List.of(new SegmentStrategy.Segment(
                        "kw-doc_seg_0", "doc-bm25", "违约责任条款与赔偿规定", 0, null)));
        when(vectorizer.vectorizeBatch(any(String[].class))).thenReturn(new float[][]{{1f}});
        when(vectorizer.vectorize(anyString())).thenReturn(new float[]{1f});
        when(vectorStore.search(any(float[].class), anyInt(), anyMap())).thenReturn(List.of());
        when(vectorStore.scrollAll()).thenReturn(List.of());

        kb.addDocumentWithSegment("doc-bm25", "违约责任条款与赔偿规定", Map.of(),
                DocumentSegmenter.StrategyType.FIXED_LENGTH);

        List<Map<String, Object>> hits = kb.hybridSearch("违约责任", 5,
                DocumentSegmenter.StrategyType.FIXED_LENGTH, Map.of());

        assertThat(hits).extracting(r -> r.get("id")).containsExactly("kw-doc_seg_0");
    }

    @Test
    void rrfFusionModePromotesDocumentsHitByBothChannels() {
        // 向量路：vec-only 第 1、kw-doc 第 2；bm25 路：kw-doc 第 1。
        // RRF：kw-doc = 1/61 + 1/62 反超 vec-only = 1/61 → kw-doc 第一；UNION 保持 vec-only 第一。
        Map<String, Object> vecOnly = Map.of("id", "vec-only", "similarity", 0.9f);
        Map<String, Object> kwHit = Map.of("id", "kw-doc_seg_0", "similarity", 0.8f);
        when(vectorizer.vectorize(anyString())).thenReturn(new float[]{1f});
        when(vectorStore.search(any(float[].class), anyInt(), anyMap()))
                .thenReturn(List.of(vecOnly, kwHit));
        when(vectorStore.scrollAll()).thenReturn(List.of(
                segmentPayload("kw-doc_seg_0", "违约责任条款与赔偿规定")));
        when(vectorStore.getPayload("kw-doc_seg_0")).thenReturn(Map.of("content", "违约责任条款与赔偿规定"));

        List<Map<String, Object>> rrf = knowledgeBase("RRF").hybridSearch("违约责任", 5,
                DocumentSegmenter.StrategyType.FIXED_LENGTH, Map.of());
        List<Map<String, Object>> union = knowledgeBase("UNION").hybridSearch("违约责任", 5,
                DocumentSegmenter.StrategyType.FIXED_LENGTH, Map.of());

        assertThat(rrf).extracting(r -> r.get("id")).containsExactly("kw-doc_seg_0", "vec-only");
        assertThat(union).extracting(r -> r.get("id")).containsExactly("vec-only", "kw-doc_seg_0");
    }

    @Test
    void wholeDocumentPointsAreExcludedFromKeywordChannelOnLoad() {
        // 与旧扫描实现一致：BM25 通道只覆盖 type=segment，整篇文档点只走向量通道
        KnowledgeBase kb = knowledgeBase("UNION");
        when(vectorizer.vectorize(anyString())).thenReturn(new float[]{1f});
        when(vectorStore.search(any(float[].class), anyInt(), anyMap())).thenReturn(List.of());
        Map<String, Object> wholeDoc = new HashMap<>(segmentPayload("short-doc", "违约责任条款"));
        wholeDoc.put("type", "document");
        when(vectorStore.scrollAll()).thenReturn(List.of(
                wholeDoc,
                segmentPayload("short-doc_seg_0", "违约责任条款")));
        when(vectorStore.getPayload("short-doc_seg_0")).thenReturn(Map.of("content", "违约责任条款"));

        List<Map<String, Object>> hits = kb.hybridSearch("违约责任", 5,
                DocumentSegmenter.StrategyType.FIXED_LENGTH, Map.of());

        assertThat(hits).extracting(r -> r.get("id")).containsExactly("short-doc_seg_0");
    }

    @Test
    void documentsStoredWithoutSegmentsAreNotKeywordIndexed() {
        // 空分段回退分支：整篇文档点只落 Qdrant，不进 BM25 通道
        KnowledgeBase kb = knowledgeBase("UNION");
        when(segmenter.segment(eq("short-doc"), anyString(), any())).thenReturn(List.of());
        when(vectorizer.vectorize(anyString())).thenReturn(new float[]{1f});
        when(vectorStore.search(any(float[].class), anyInt(), anyMap())).thenReturn(List.of());
        when(vectorStore.scrollAll()).thenReturn(List.of());

        kb.addDocumentWithSegment("short-doc", "违约责任条款", Map.of(),
                DocumentSegmenter.StrategyType.FIXED_LENGTH);

        verify(vectorStore).store(eq("short-doc"), any(float[].class), anyMap());
        assertThat(kb.hybridSearch("违约责任", 5, DocumentSegmenter.StrategyType.FIXED_LENGTH, Map.of()))
                .isEmpty();
    }
}
