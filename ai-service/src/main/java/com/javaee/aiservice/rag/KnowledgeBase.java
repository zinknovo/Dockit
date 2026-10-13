package com.javaee.aiservice.rag;

import com.javaee.aiservice.aiops.MonitoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 知识库：文档切分 → 向量化 → 存入 Qdrant。
 * 向量、原文内容、元数据、分段映射全部落在 Qdrant 点的 payload 中（命中即取），
 * 不再依赖 Redis 存储知识库数据。
 * <p>
 * BM25 关键词通道由内存倒排索引（{@link InvertedIndex}）承载：写入/删除时同步增删，
 * 首次查询前从 Qdrant 一次性装载存量分段，替代此前"每次查询全量滚动打分"的实现。
 */
@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private final DocumentVectorizer vectorizer;

    private final VectorStore vectorStore;

    private final Reranker reranker;

    private final DocumentSegmenter documentSegmenter;

    private final MonitoringService monitoringService;

    private final InvertedIndex invertedIndex;

    private final RecallFusion.Mode fusionMode;

    /** BM25 索引是否已完成首次装载（Qdrant 存量分段 → 内存倒排） */
    private volatile boolean bm25IndexLoaded = false;

    @Autowired
    public KnowledgeBase(DocumentVectorizer vectorizer, VectorStore vectorStore, Reranker reranker,
                         DocumentSegmenter documentSegmenter, MonitoringService monitoringService,
                         InvertedIndex invertedIndex,
                         @Value("${ai.rag.fusion-mode:RRF}") String fusionMode) {
        this.vectorizer = vectorizer;
        this.vectorStore = vectorStore;
        this.reranker = reranker;
        this.documentSegmenter = documentSegmenter;
        this.monitoringService = monitoringService;
        this.invertedIndex = invertedIndex;
        this.fusionMode = parseFusionMode(fusionMode);
    }

    private static RecallFusion.Mode parseFusionMode(String fusionMode) {
        try {
            return RecallFusion.Mode.valueOf(fusionMode.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            log.warn("未知融合模式 {}，回退 UNION", fusionMode);
            return RecallFusion.Mode.UNION;
        }
    }

    public void addDocument(String documentId, String content, Map<String, Object> metadata) {
        addDocumentWithSegment(documentId, content, metadata, DocumentSegmenter.StrategyType.AUTO);
    }

    public void addDocumentWithSegment(String documentId, String content, Map<String, Object> metadata,
                                       DocumentSegmenter.StrategyType strategyType) {
        log.info("添加文档到知识库: documentId={}, strategy={}", documentId, strategyType);

        List<String> storedIds = new ArrayList<>();
        try {
            Map<String, Object> docMetadata = normalizeMetadata(metadata);
            docMetadata.put("strategy", strategyType.name());
            docMetadata.put("totalLength", content.length());
            docMetadata.put("type", "document");

            List<SegmentStrategy.Segment> segments = documentSegmenter.segment(documentId, content, strategyType);

            if (segments.isEmpty()) {
                log.warn("文档分段结果为空，直接存储完整文档");
                docMetadata.put("documentId", documentId);
                docMetadata.put("content", content);
                float[] vector = vectorizer.vectorize(content);
                vectorStore.store(documentId, vector, docMetadata);
                // 整篇文档点不进 BM25 通道（与旧扫描实现一致：关键词通道只覆盖分段点）；
                // 该分支也无 storedIds 记录，若在此索引、索引失败将残留 Qdrant 孤儿点
                return;
            }

            String[] segmentContents = new String[segments.size()];
            for (int i = 0; i < segments.size(); i++) {
                segmentContents[i] = segments.get(i).getContent();
            }
            // 一次请求批量向量化所有分段，避免逐条调用触发账户 QPS 限流
            float[][] vectors = vectorizer.vectorizeBatch(segmentContents);
            for (int i = 0; i < segments.size(); i++) {
                SegmentStrategy.Segment segment = segments.get(i);
                String segmentId = segment.getSegmentId();

                Map<String, Object> segmentMetadata = new HashMap<>(docMetadata);
                segmentMetadata.put("documentId", documentId);
                segmentMetadata.put("segmentIndex", segment.getIndex());
                segmentMetadata.put("segmentTitle", segment.getTitle());
                segmentMetadata.put("charCount", segment.getCharCount());
                segmentMetadata.put("type", "segment");
                segmentMetadata.put("content", segment.getContent());

                vectorStore.store(segmentId, vectors[i], segmentMetadata);
                storedIds.add(segmentId);
                indexKeywordChannel(segmentId, segment.getContent(), segmentMetadata);
            }

            // 不建文档级点：分段点 payload 已携带完整文档元数据（documentId/strategy/userId 等），
            // 文档级查询通过按 documentId 过滤分段点推导；省 1 次整文档向量化 + Qdrant 存储。
            log.info("文档添加成功: documentId={}, 分段数={}", documentId, segments.size());
        } catch (Exception e) {
            // 回滚已写入的分段点，避免失败后残留孤儿向量
            for (String storedId : storedIds) {
                invertedIndex.remove(storedId);
                try {
                    vectorStore.delete(storedId);
                } catch (Exception cleanup) {
                    log.warn("回滚分段点失败: id={}", storedId, cleanup);
                }
            }
            if (!storedIds.isEmpty()) {
                log.warn("添加文档失败，已回滚 {} 个已写入分段点: documentId={}", storedIds.size(), documentId);
            }
            log.error("添加文档失败", e);
            throw new RuntimeException("添加文档失败: " + e.getMessage(), e);
        }
    }

    public void addDocument(String documentId, String content, Map<String, Object> metadata,
                           DocumentSegmenter.StrategyType strategyType) {
        addDocumentWithSegment(documentId, content, metadata, strategyType);
    }

    public void removeDocument(String documentId) {
        log.info("从知识库移除文档: documentId={}", documentId);

        try {
            List<String> segmentIds = getSegmentIds(documentId);
            for (String segmentId : segmentIds) {
                invertedIndex.remove(segmentId);
                vectorStore.delete(segmentId);
            }
            vectorStore.delete(documentId);
            invertedIndex.remove(documentId);
            log.info("文档移除成功: documentId={}, 删除了{}个分段", documentId, segmentIds.size());
        } catch (Exception e) {
            log.error("移除文档失败", e);
            throw new RuntimeException("移除文档失败: " + e.getMessage(), e);
        }
    }

    public String getDocumentContent(String documentId) {
        List<String> segmentIds = getSegmentIds(documentId);
        if (segmentIds.isEmpty()) {
            // 无分段（整篇存储）的文档：内容在文档级点
            Object content = vectorStore.getPayload(documentId).get("content");
            return content == null ? null : content.toString();
        }
        StringBuilder builder = new StringBuilder();
        for (String segmentId : segmentIds) {
            Object content = vectorStore.getPayload(segmentId).get("content");
            if (content != null) {
                if (!builder.isEmpty()) {
                    builder.append("\n\n");
                }
                builder.append(content);
            }
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    public String getSegmentContent(String segmentId) {
        Object content = vectorStore.getPayload(segmentId).get("content");
        return content == null ? null : content.toString();
    }

    public List<String> getSegmentIds(String documentId) {
        return vectorStore.scrollAll(Map.of("type", "segment", "documentId", documentId)).stream()
                .sorted(Comparator.comparingDouble(p -> ((Number) p.getOrDefault("segmentIndex", -1)).doubleValue()))
                .map(p -> p.get("rawId").toString())
                .toList();
    }

    public Map<String, Object> getDocumentMetadata(String documentId) {
        // 文档元数据复制在分段点 payload 上；无分段（短文档）时存在 type=document 的文档点
        return vectorStore.scrollAll(Map.of("documentId", documentId)).stream()
                .findFirst()
                .map(p -> {
                    Map<String, Object> metadata = new HashMap<>(p);
                    metadata.remove("content");
                    metadata.remove("rawId");
                    metadata.remove("segmentIndex");
                    metadata.remove("segmentTitle");
                    metadata.remove("charCount");
                    metadata.remove("type");
                    return metadata;
                })
                .orElse(Collections.emptyMap());
    }

    public List<Map<String, Object>> getDocumentSegments(String documentId) {
        log.info("获取文档分段: documentId={}", documentId);

        try {
            List<String> segmentIds = getSegmentIds(documentId);
            List<Map<String, Object>> segments = new ArrayList<>();

            for (String segmentId : segmentIds) {
                Map<String, Object> segmentInfo = new HashMap<>();
                segmentInfo.put("segmentId", segmentId);
                segmentInfo.put("content", getSegmentContent(segmentId));
                segments.add(segmentInfo);
            }

            return segments;
        } catch (Exception e) {
            log.error("获取文档分段失败", e);
            throw new RuntimeException("获取文档分段失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> search(String query, int topK) {
        return search(query, topK, DocumentSegmenter.StrategyType.CHAPTER);
    }

    public List<Map<String, Object>> search(String query, int topK,
                                            DocumentSegmenter.StrategyType strategyType) {
        return search(query, topK, strategyType, Collections.emptyMap());
    }

    public List<Map<String, Object>> search(String query, int topK,
                                            DocumentSegmenter.StrategyType strategyType,
                                            Map<String, Object> filters) {
        log.info("搜索知识库: query={}, topK={}, strategy={}", query, topK, strategyType);

        long start = System.currentTimeMillis();
        try {
            long t0 = System.currentTimeMillis();
            float[] queryVector = vectorizer.vectorize(query);
            monitoringService.recordTimer("rag.embedding", System.currentTimeMillis() - t0);

            long t1 = System.currentTimeMillis();
            List<Map<String, Object>> results = vectorStore.search(queryVector, topK, filters);
            monitoringService.recordTimer("rag.vector-search", System.currentTimeMillis() - t1);

            // payload 已带 content；兜底：缺失时按 id 从 Qdrant 补取
            backfillContent(results);

            return results;
        } catch (Exception e) {
            log.error("知识库搜索失败", e);
            throw new RuntimeException("知识库搜索失败: " + e.getMessage(), e);
        } finally {
            monitoringService.recordTimer("rag.search", System.currentTimeMillis() - start);
        }
    }

    public List<Map<String, Object>> hybridSearch(String query, int topK,
                                                   DocumentSegmenter.StrategyType strategyType,
                                                   Map<String, Object> filters) {
        log.info("混合检索: query={}, topK={}, strategy={}", query, topK, strategyType);

        try {
            long t0 = System.currentTimeMillis();
            float[] queryVector = vectorizer.vectorize(query);
            monitoringService.recordTimer("rag.embedding", System.currentTimeMillis() - t0);

            long t1 = System.currentTimeMillis();
            List<Map<String, Object>> vectorResults = vectorStore.search(queryVector, topK * 3, filters);
            monitoringService.recordTimer("rag.vector-search", System.currentTimeMillis() - t1);

            List<Map<String, Object>> bm25Results = bm25Search(query, topK * 3, filters);

            List<Map<String, Object>> combinedResults = RecallFusion.fuse(vectorResults, bm25Results, topK, fusionMode);
            backfillContent(combinedResults);

            return combinedResults;

        } catch (Exception e) {
            log.error("混合检索失败", e);
            throw new RuntimeException("混合检索失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                            Reranker.RerankStrategy rerankStrategy,
                                                            DocumentSegmenter.StrategyType strategyType) {
        return hybridSearchWithRerank(query, topK, rerankStrategy, strategyType, Collections.emptyMap());
    }

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                            Reranker.RerankStrategy rerankStrategy,
                                                            DocumentSegmenter.StrategyType strategyType,
                                                            Map<String, Object> filters) {
        log.info("混合检索加重排序: query={}, topK={}, strategy={}, rerankStrategy={}",
                query, topK, strategyType, rerankStrategy);

        try {
            List<Map<String, Object>> candidates = hybridSearch(query, topK * 3, strategyType, filters);

            long t0 = System.currentTimeMillis();
            List<Map<String, Object>> results = reranker.rerank(query, candidates, rerankStrategy, topK);
            monitoringService.recordTimer("rag.rerank", System.currentTimeMillis() - t0);

            return results;
        } catch (Exception e) {
            log.error("混合检索加重排序失败", e);
            throw new RuntimeException("混合检索加重排序失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                            Reranker.RerankStrategy rerankStrategy,
                                                            String userId,
                                                            String knowledgeBaseId) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("userId", userId);
        filters.put("knowledgeBaseId", knowledgeBaseId);
        return hybridSearchWithRerank(query, topK, rerankStrategy, DocumentSegmenter.StrategyType.CHAPTER, filters);
    }

    /**
     * BM25 关键词检索：内存倒排索引打分（真实 DF/IDF）。
     * 首次调用前从 Qdrant 一次性装载存量分段，此后写入/删除随 {@link #addDocumentWithSegment}
     * 与 {@link #removeDocument} 同步维护。
     */
    private List<Map<String, Object>> bm25Search(String query, int topK, Map<String, Object> filters) {
        ensureBm25IndexLoaded();
        return invertedIndex.search(query, topK, filters);
    }

    private void ensureBm25IndexLoaded() {
        if (bm25IndexLoaded) {
            return;
        }
        synchronized (this) {
            if (bm25IndexLoaded) {
                return;
            }
            // 先置位再装载：Qdrant 不可用时 scrollAll 返回空列表，避免每次查询重试全量扫描
            bm25IndexLoaded = true;
            invertedIndex.loadFrom(vectorStore.scrollAll());
            log.info("BM25 倒排索引装载完成: {} 个分段/文档", invertedIndex.size());
        }
    }

    /**
     * 同步内存倒排索引（忽略 content 自身，其余元数据保留供过滤）
     */
    private void indexKeywordChannel(String id, String content, Map<String, Object> metadata) {
        Map<String, Object> filterable = new HashMap<>(metadata);
        filterable.remove("content");
        invertedIndex.index(id, content, filterable);
    }

    public List<String> getAllDocumentIds(String userId, String knowledgeBaseId) {
        return distinctDocumentIds(Map.of(
                "userId", userId,
                "knowledgeBaseId", knowledgeBaseId
        ));
    }

    /**
     * 文档 = 分段点的 documentId 去重（有分段）+ type=document 的点（短文档无分段）
     */
    private List<String> distinctDocumentIds(Map<String, Object> filters) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> payload : vectorStore.scrollAll()) {
            if (!PayloadFilters.matches(payload, filters)) {
                continue;
            }
            if ("segment".equals(payload.get("type"))) {
                Object documentId = payload.get("documentId");
                if (documentId != null) {
                    ids.add(documentId.toString());
                }
            } else if ("document".equals(payload.get("type"))) {
                Object rawId = payload.get("rawId");
                if (rawId != null) {
                    ids.add(rawId.toString());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    public Map<String, Object> getStatistics(String userId, String knowledgeBaseId) {
        Map<String, Object> stats = new HashMap<>();

        List<Map<String, Object>> all = vectorStore.scrollAll();
        Map<String, Object> filters = (userId == null || knowledgeBaseId == null)
                ? Collections.emptyMap()
                : Map.of("userId", userId, "knowledgeBaseId", knowledgeBaseId);

        long segmentCount = all.stream()
                .filter(p -> "segment".equals(p.get("type")))
                .filter(p -> PayloadFilters.matches(p, filters))
                .count();

        stats.put("documentCount", distinctDocumentIds(filters).size());
        stats.put("segmentCount", segmentCount);

        long totalContentSize = 0;
        for (Map<String, Object> p : all) {
            Object content = p.get("content");
            if (content != null) {
                totalContentSize += content.toString().length();
            }
        }
        stats.put("totalContentSize", totalContentSize);

        return stats;
    }

    /**
     * 命中结果补 content：向量通道 payload 已带 content；BM25 独有候选或缺失时
     * 按 id 从 Qdrant 补取（分段点 → 整篇文档点兜底），仍缺失则不写键，
     * 避免下游把显式 null 当作内容拼进提示词。
     */
    private void backfillContent(List<Map<String, Object>> results) {
        for (Map<String, Object> result : results) {
            if (result.get("content") == null) {
                String id = (String) result.get("id");
                String content = getSegmentContent(id);
                if (content == null) {
                    content = getDocumentContent(id);
                }
                if (content != null) {
                    result.put("content", content);
                }
            }
        }
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        Map<String, Object> normalized = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        normalized.putIfAbsent("userId", "system");
        normalized.putIfAbsent("knowledgeBaseId", "default");
        return normalized;
    }
}
