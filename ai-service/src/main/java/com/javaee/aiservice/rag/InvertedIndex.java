package com.javaee.aiservice.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 内存倒排索引 + BM25 打分，替代"每次查询全量滚动 Qdrant 再逐条打分"的简化实现。
 * <p>
 * 分词：连续 CJK 字符按二元组（bigram）切分（单字自成一项），拉丁字符按小写词切分。
 * 统计：DF（词项文档频率）来自 posting list 长度，IDF 用 Lucene 变体
 * {@code ln(1 + (N - df + 0.5) / (df + 0.5))}（恒为正），文档长度归一化采用标准 k1/b 参数。
 * <p>
 * 线程安全：写（index/remove/loadFrom）与读（search）方法级 synchronized，写读互斥，
 * 索引量级（万级分段）下单次打分亚毫秒，串行化开销可忽略。
 */
@Component
public class InvertedIndex {

    private static final float K1 = 1.2f;
    private static final float B = 0.75f;

    /** posting：term → 出现该词项的文档及其词频 */
    private final Map<String, List<Posting>> postings = new HashMap<>();
    /** docId → 分词后的词项计数 */
    private final Map<String, Map<String, Integer>> docTermFreqs = new HashMap<>();
    /** docId → 文档长度（词项数） */
    private final Map<String, Integer> docLengths = new HashMap<>();
    /** docId → 参与过滤的元数据（type/documentId/userId/knowledgeBaseId 等） */
    private final Map<String, Map<String, Object>> docMetadata = new HashMap<>();

    /**
     * 从 Qdrant 存量 payload 装载（幂等 upsert：同 id 覆盖，不存在的 id 不受影响）。
     * 不做清空重建——装载前经 API 写入的条目不应被清掉。
     * <p>
     * 仅装载 {@code type=segment} 的分段点：与旧扫描实现一致，整篇文档点（type=document，
     * 短文档无分段时落库）只走向量通道，避免 BM25 通道把整篇文档当分段召回、抬高 IDF 基数。
     */
    public void loadFrom(List<Map<String, Object>> payloads) {
        if (payloads == null) {
            return;
        }
        for (Map<String, Object> payload : payloads) {
            String id = payload.get("rawId") == null ? null : payload.get("rawId").toString();
            Object content = payload.get("content");
            if (id == null || content == null || !"segment".equals(payload.get("type"))) {
                continue;
            }
            Map<String, Object> metadata = new HashMap<>(payload);
            metadata.remove("content");
            index(id, content.toString(), metadata);
        }
    }

    public synchronized void index(String id, String content, Map<String, Object> filterableMetadata) {
        remove(id);
        if (id == null || content == null || content.isEmpty()) {
            return;
        }
        Map<String, Integer> termFreqs = termFrequencies(tokenize(content));
        if (termFreqs.isEmpty()) {
            return;
        }
        docTermFreqs.put(id, termFreqs);
        docLengths.put(id, termFreqs.values().stream().mapToInt(Integer::intValue).sum());
        // 元数据可能带 null 值（如 segmentTitle），Map.copyOf 不接受；过滤后做只读快照
        Map<String, Object> safeMetadata = new HashMap<>();
        if (filterableMetadata != null) {
            filterableMetadata.forEach((k, v) -> {
                if (v != null) {
                    safeMetadata.put(k, v);
                }
            });
        }
        docMetadata.put(id, Collections.unmodifiableMap(safeMetadata));
        for (Map.Entry<String, Integer> e : termFreqs.entrySet()) {
            postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new Posting(id, e.getValue()));
        }
    }

    public synchronized void remove(String id) {
        Map<String, Integer> termFreqs = docTermFreqs.remove(id);
        if (termFreqs == null) {
            return;
        }
        docLengths.remove(id);
        docMetadata.remove(id);
        for (String term : termFreqs.keySet()) {
            List<Posting> list = postings.get(term);
            if (list != null) {
                list.removeIf(p -> p.docId.equals(id));
                if (list.isEmpty()) {
                    postings.remove(term);
                }
            }
        }
    }

    public synchronized List<Map<String, Object>> search(String query, int topK, Map<String, Object> filters) {
        if (query == null || query.isBlank() || docTermFreqs.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<String> queryTerms = new ArrayList<>(new LinkedHashSet<>(tokenize(query)));
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        int docCount = docTermFreqs.size();
        double avgDocLength = docLengths.values().stream().mapToInt(Integer::intValue).average().orElse(1.0);

        // 累加器：遍历每个查询词项的 posting list，只触碰包含词项的文档
        Map<String, Float> scores = new HashMap<>();
        for (String term : queryTerms) {
            List<Posting> list = postings.get(term);
            if (list == null) {
                continue;
            }
            float idf = idf(list.size(), docCount);
            for (Posting posting : list) {
                if (!PayloadFilters.matches(docMetadata.get(posting.docId), filters)) {
                    continue;
                }
                int docLength = docLengths.getOrDefault(posting.docId, 1);
                double tfNorm = posting.tf * (K1 + 1)
                        / (posting.tf + K1 * (1 - B + B * docLength / avgDocLength));
                scores.merge(posting.docId, (float) (idf * tfNorm), Float::sum);
            }
        }

        return scores.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(topK)
                .map(e -> Map.<String, Object>of("id", e.getKey(), "similarity", e.getValue()))
                .toList();
    }

    public synchronized int size() {
        return docTermFreqs.size();
    }

    /** Lucene 变体 IDF，df 越大分越低，恒为正 */
    private float idf(int df, int docCount) {
        return (float) Math.log(1 + (docCount - df + 0.5) / (df + 0.5));
    }

    private Map<String, Integer> termFrequencies(List<String> terms) {
        Map<String, Integer> freqs = new HashMap<>();
        for (String term : terms) {
            freqs.merge(term, 1, Integer::sum);
        }
        return freqs;
    }

    /**
     * 分词：小写化后按连续 CJK / 连续拉丁字符切成片段。
     * CJK 片段输出字符二元组（单字片段输出自身）；拉丁片段输出 {@code [a-z0-9]+} 词。
     */
    static List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return terms;
        }
        String lower = text.toLowerCase();
        int i = 0;
        while (i < lower.length()) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                int start = i;
                while (i < lower.length() && isCjk(lower.charAt(i))) {
                    i++;
                }
                addCjkTerms(terms, lower.substring(start, i));
            } else if (Character.isLetterOrDigit(c)) {
                int start = i;
                while (i < lower.length() && Character.isLetterOrDigit(lower.charAt(i))) {
                    i++;
                }
                terms.add(lower.substring(start, i));
            } else {
                i++;
            }
        }
        return terms;
    }

    private static void addCjkTerms(List<String> terms, String run) {
        if (run.length() == 1) {
            terms.add(run);
            return;
        }
        for (int i = 0; i + 2 <= run.length(); i++) {
            terms.add(run.substring(i, i + 2));
        }
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    private record Posting(String docId, int tf) {
    }
}
