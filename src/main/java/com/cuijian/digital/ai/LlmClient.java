package com.cuijian.digital.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 大模型客户端。
 *
 * 只依赖 OpenAI 兼容的 /chat/completions 协议，
 * 因此换成 DeepSeek、通义、Kimi、本地 Ollama 都只要改 application.yml 里的 base-url 和 model。
 * 没配 api-key 时 available() 返回 false，上层自动降级到本地检索，不会让接口直接 500。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public LlmClient(
            @Value("${app.ai.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.model:deepseek-chat}") String model,
            @Value("${app.ai.timeout-seconds:25}") int timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    public boolean available() {
        return !apiKey.isEmpty();
    }

    public String modelName() {
        return model;
    }

    /**
     * 调大模型生成答案。
     * 失败直接抛异常，由调用方决定是降级还是报错 —— 客户端不替业务做决定。
     */
    public String chat(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.2);
        body.put("max_tokens", 800);

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("大模型返回 HTTP " + resp.statusCode()
                    + "：" + abbreviate(resp.body()));
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("大模型返回内容为空");
        }
        return content.asText().trim();
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** 便于排查：不暴露 key，只给个是否配置了的标记 */
    public Map<String, Object> desc() {
        return Map.of(
                "configured", available(),
                "baseUrl", baseUrl,
                "model", model,
                "timeoutSeconds", timeoutSeconds);
    }
}
