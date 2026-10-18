package com.cuijian.digital.domain;

import java.time.LocalDateTime;

/** 知识库切片，RAG 检索的最小单位 */
public record KbChunk(
        Long id,
        Long docId,
        String docTitle,
        Integer seq,
        String content,
        double score,
        LocalDateTime createdAt
) {
    public KbChunk withScore(double newScore) {
        return new KbChunk(id, docId, docTitle, seq, content, newScore, createdAt);
    }
}
