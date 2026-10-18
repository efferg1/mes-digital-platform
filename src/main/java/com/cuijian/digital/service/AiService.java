package com.cuijian.digital.service;

import com.cuijian.digital.ai.KeywordRetriever;
import com.cuijian.digital.ai.LlmClient;
import com.cuijian.digital.ai.TextChunker;
import com.cuijian.digital.dao.KbDao;
import com.cuijian.digital.domain.KbChunk;
import com.cuijian.digital.dto.AiAnswer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库问答（RAG）。
 *
 * 链路：文档 -> 切片 -> 检索 topK -> 拼 Prompt -> 大模型生成 -> 返回答案 + 出处
 *
 * 两个设计要点：
 *   1. 检索和生成分离。检索负责「找对资料」，模型负责「说人话」。
 *      模型只允许基于检索到的片段回答，避免它拿训练数据里的通用知识瞎编。
 *   2. 模型不可用时降级成检索结果直出。接口永远有返回，
 *      不会因为没配 Key 或模型超时就让整个功能不可用。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private static final String SYSTEM_PROMPT = """
            你是制造业数字化平台的内置知识助手，服务于车间操作工、班长和计划员。
            规则：
            1. 只能依据【参考资料】回答，资料里没有的内容直接说“现有资料未覆盖，建议联系工艺/设备工程师确认”，不要编造；
            2. 回答用中文，分条列出，关键参数（温度、数量、时间、状态）要保留原文数值；
            3. 控制在 200 字以内，先给结论再给依据；
            4. 不要输出“根据参考资料”这类套话，直接说事。
            """;

    private final KbDao kbDao;
    private final LlmClient llm;

    @Value("${app.ai.max-chunks:4}")
    private int maxChunks;

    @Value("${app.ai.max-chunk-chars:700}")
    private int maxChunkChars;

    public AiService(KbDao kbDao, LlmClient llm) {
        this.kbDao = kbDao;
        this.llm = llm;
    }

    /** 启动时如果切片表是空的，自动建一次索引，保证 clone 下来就能问 */
    @PostConstruct
    public void initIndexIfEmpty() {
        try {
            long chunks = kbDao.countChunks();
            if (chunks == 0) {
                int n = reindex();
                log.info("知识库索引已初始化，生成 {} 个切片", n);
            } else {
                log.info("知识库已有 {} 个切片，跳过初始化", chunks);
            }
        } catch (Exception e) {
            log.warn("知识库索引初始化失败（不影响其他功能）：{}", e.getMessage());
        }
    }

    /** 重建索引：清空切片 -> 逐篇文档切片入库 */
    public int reindex() {
        kbDao.deleteAllChunks();
        List<Map<String, Object>> docs = kbDao.findAllRawDocuments();
        int total = 0;
        for (Map<String, Object> doc : docs) {
            long docId = ((Number) doc.get("id")).longValue();
            String content = String.valueOf(doc.get("content"));
            List<String> parts = TextChunker.split(content);
            for (int i = 0; i < parts.size(); i++) {
                kbDao.insertChunk(docId, i + 1, parts.get(i));
                total++;
            }
        }
        return total;
    }

    // ==================== 问答 ====================

    public AiAnswer ask(String question, int topK) {
        long start = System.currentTimeMillis();

        int k = Math.min(Math.max(topK, 1), maxChunks * 2);
        List<KbChunk> hits = retrieve(question, k);

        if (hits.isEmpty()) {
            return new AiAnswer(
                    "现有资料未覆盖这个问题，建议联系工艺或设备工程师确认。",
                    "NO_HIT", llm.modelName(), List.of(),
                    System.currentTimeMillis() - start);
        }

        List<AiAnswer.Source> sources = hits.stream().map(AiAnswer.Source::of).toList();

        // 有 Key 就走大模型
        if (llm.available()) {
            try {
                String answer = llm.chat(SYSTEM_PROMPT, buildUserPrompt(question, hits));
                return new AiAnswer(answer, "LLM", llm.modelName(), sources,
                        System.currentTimeMillis() - start);
            } catch (Exception e) {
                // 模型超时 / 额度用尽 / 网络不通，都退到本地检索，而不是把错误抛给前端
                log.warn("大模型调用失败，降级为本地检索：{}", e.getMessage());
                return new AiAnswer(
                        fallbackAnswer(question, hits, "（大模型调用失败，以下为知识库原文摘录）"),
                        "RETRIEVAL_FALLBACK", llm.modelName(), sources,
                        System.currentTimeMillis() - start);
            }
        }

        return new AiAnswer(fallbackAnswer(question, hits, null),
                "RETRIEVAL", llm.modelName(), sources,
                System.currentTimeMillis() - start);
    }

    /** 检索，按配置截断片段长度，避免 prompt 过长把 token 打爆 */
    private List<KbChunk> retrieve(String question, int k) {
        List<KbChunk> all = kbDao.findAllChunks();
        List<KbChunk> hits = KeywordRetriever.search(all, question, k);
        List<KbChunk> trimmed = new ArrayList<>(hits.size());
        for (KbChunk c : hits) {
            String text = c.content();
            if (text.length() > maxChunkChars) {
                text = text.substring(0, maxChunkChars) + "…";
            }
            trimmed.add(new KbChunk(c.id(), c.docId(), c.docTitle(), c.seq(), text, c.score(), c.createdAt()));
        }
        return trimmed;
    }

    /** 拼 RAG 的 user prompt：参考资料编号 + 原文 + 问题 */
    private String buildUserPrompt(String question, List<KbChunk> hits) {
        StringBuilder sb = new StringBuilder("【参考资料】\n");
        int i = 1;
        for (KbChunk c : hits) {
            sb.append('[').append(i++).append("] 《").append(c.docTitle()).append("》 第")
                    .append(c.seq()).append("段\n")
                    .append(c.content()).append("\n\n");
        }
        sb.append("【问题】").append(question);
        return sb.toString();
    }

    /** 无模型时的答案：给出最相关的原文摘要，标注出处，用户自己核对 */
    private String fallbackAnswer(String question, List<KbChunk> hits, String prefix) {
        StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix).append("\n\n");
        }
        sb.append("与「").append(question).append("」最相关的资料：\n");
        // 降级模式最多列 3 条、每条截到 160 字：
        // 整段原文全贴出来在聊天框里读不完，不如给摘要 + 出处让人自己去核
        int limit = Math.min(hits.size(), 3);
        for (int i = 0; i < limit; i++) {
            KbChunk c = hits.get(i);
            String text = c.content();
            if (text.length() > 160) {
                text = text.substring(0, 160) + "…";
            }
            sb.append(i + 1).append(". 《").append(c.docTitle()).append("》第")
                    .append(c.seq()).append("段：").append(text).append('\n');
        }
        sb.append("\n（本地检索模式，未接入大模型。配 app.ai.api-key 后改为生成式回答）");
        return sb.toString();
    }

    // ==================== 状态 ====================

    public Map<String, Object> status() {
        return Map.of(
                "documents", kbDao.countDocuments(),
                "chunks", kbDao.countChunks(),
                "llm", llm.desc(),
                "retriever", "BM25 + 2-gram（无外部依赖）");
    }

    public List<Map<String, Object>> documents() {
        return kbDao.findAllDocuments();
    }
}
