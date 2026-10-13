package com.javaee.aiservice.rag;

import java.util.Map;

/**
 * Qdrant payload / 倒排元数据的过滤匹配语义，{@link KnowledgeBase} 与 {@link InvertedIndex} 共用：
 * 过滤值为 null/空白视为跳过该条件；实际值缺失或与期望值字符串不等即不匹配。
 */
final class PayloadFilters {

    private PayloadFilters() {
    }

    static boolean matches(Map<String, Object> metadata, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object expected = filter.getValue();
            if (expected == null || expected.toString().isBlank()) {
                continue;
            }
            Object actual = metadata == null ? null : metadata.get(filter.getKey());
            if (actual == null || !expected.toString().equals(actual.toString())) {
                return false;
            }
        }
        return true;
    }
}
