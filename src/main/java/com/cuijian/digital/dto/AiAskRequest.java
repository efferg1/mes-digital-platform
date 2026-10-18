package com.cuijian.digital.dto;

import jakarta.validation.constraints.NotBlank;

/** AI 问答入参 */
public record AiAskRequest(
        @NotBlank(message = "问题不能为空") String question,
        Integer topK
) {
    public int topKOrDefault() {
        if (topK == null || topK <= 0) {
            return 4;
        }
        return Math.min(topK, 8);
    }
}
