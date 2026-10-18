package com.cuijian.digital.web;

import com.cuijian.digital.common.ApiResponse;
import com.cuijian.digital.dto.AiAnswer;
import com.cuijian.digital.dto.AiAskRequest;
import com.cuijian.digital.service.AiService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 知识库问答（RAG） */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    /** 提问：返回答案 + 命中的原文片段（带出处，可核对） */
    @PostMapping("/ask")
    public ApiResponse<AiAnswer> ask(@Valid @RequestBody AiAskRequest req) {
        return ApiResponse.ok(aiService.ask(req.question(), req.topKOrDefault()));
    }

    /** 重建切片索引，文档更新后调一次 */
    @PostMapping("/reindex")
    public ApiResponse<Map<String, Object>> reindex() {
        int n = aiService.reindex();
        return ApiResponse.ok("索引重建完成", Map.of("chunks", n));
    }

    /** 当前知识库与模型状态 */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(aiService.status());
    }

    /** 知识库文档清单 */
    @GetMapping("/documents")
    public ApiResponse<List<Map<String, Object>>> documents() {
        return ApiResponse.ok(aiService.documents());
    }
}
