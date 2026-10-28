package com.minke.mallmanagement.chat.service;

import java.util.List;

/**
 * 向量化接口 —— 生产实现是 {@link ChatEmbeddingClient}，单测用内存替身。
 *
 * <p>只做「文本 → 向量」，不掺任何意图 / 路由逻辑；失败时抛 {@link EmbeddingException}，
 * 由 {@link SemanticRouter} 决定降级（返回 {@code null} 让编排层走旧分类器）。
 */
interface TextEmbedder {

    boolean isEnabled();

    /**
     * 批量向量化；返回顺序必须与入参一致。
     *
     * @throws EmbeddingException 未启用 / 超时 / 非 200 / 响应结构不对
     */
    List<float[]> embed(List<String> texts);

    class EmbeddingException extends RuntimeException {
        EmbeddingException(String message) {
            super(message);
        }

        EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
