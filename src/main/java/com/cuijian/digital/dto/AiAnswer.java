package com.cuijian.digital.dto;

import com.cuijian.digital.domain.KbChunk;

import java.util.List;

/**
 * AI 问答结果。
 * mode 说明这次是走了大模型（LLM）还是降级成了本地检索（RETRIEVAL），
 * 排查问题时第一眼就能看出是哪条链路。
 */
public record AiAnswer(
        String answer,
        String mode,
        String model,
        List<Source> sources,
        long elapsedMs
) {

    public record Source(String docTitle, int seq, double score, String snippet) {
        public static Source of(KbChunk c) {
            String text = c.content();
            String snippet = text.length() > 80 ? text.substring(0, 80) + "…" : text;
            return new Source(c.docTitle(), c.seq(), Math.round(c.score() * 1000) / 1000.0, snippet);
        }
    }
}
