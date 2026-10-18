package com.cuijian.digital.ai;

import com.cuijian.digital.domain.KbChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关键词检索（BM25 简化版）。
 *
 * 为什么不用向量检索：
 *   1. 演示项目不能强制用户配 embedding 的 API Key，否则 clone 下来跑不动；
 *   2. 中文短问答里，关键词命中 + IDF 加权的效果已经够用，
 *      而且完全可解释 —— 面试时能说清楚每个片段为什么被选中。
 *
 * 中文没有天然分词，这里用二元组（2-gram）滑动窗口：
 *   「注塑机报警」-> 注塑 / 塑机 / 机报 / 报警
 * 牺牲一点精度，换来不引第三方分词库也能工作。
 * 真正上生产应该换成向量库（Milvus / pgvector）+ embedding。
 */
public final class KeywordRetriever {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private KeywordRetriever() {
    }

    /**
     * @param chunks 全部切片
     * @param query  用户问题
     * @param topK   返回条数
     */
    public static List<KbChunk> search(List<KbChunk> chunks, String query, int topK) {
        if (chunks.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        Set<String> qTokens = tokenize(query);
        if (qTokens.isEmpty()) {
            return List.of();
        }

        // 文档频率，用于算 IDF：越少见的词，命中后权重越高
        Map<String, Integer> df = new HashMap<>();
        List<Set<String>> docTokens = new ArrayList<>(chunks.size());
        double totalLen = 0;
        for (KbChunk c : chunks) {
            Set<String> ts = tokenize(c.content());
            docTokens.add(ts);
            totalLen += c.content().length();
            for (String t : ts) {
                df.merge(t, 1, Integer::sum);
            }
        }
        double avgLen = totalLen / chunks.size();
        int n = chunks.size();

        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            KbChunk c = chunks.get(i);
            Set<String> ts = docTokens.get(i);
            if (ts.isEmpty()) {
                continue;
            }

            String lowerContent = c.content().toLowerCase();
            double score = 0;
            for (String t : qTokens) {
                if (!ts.contains(t)) {
                    continue;
                }
                int dfi = df.getOrDefault(t, 0);
                double idf = Math.log(1 + (n - dfi + 0.5) / (dfi + 0.5));
                // 真实词频：同一个词在片段里出现多次，说明这段就是讲这个的
                double tf = countOccurrences(lowerContent, t);
                double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + c.content().length() / avgLen));
                score += idf * norm;
            }

            // 标题命中加权：问「报工」时，标题是《报工与库存扣减业务规则》的片段必须排前面。
            // 每个命中的词都累加，而不是命中一个就跳出 —— 否则长标题反而占便宜。
            if (c.docTitle() != null) {
                String title = c.docTitle().toLowerCase();
                for (String t : qTokens) {
                    if (title.contains(t)) {
                        score += 1.8;
                    }
                }
            }

            if (score > 0) {
                scored.add(new Scored(c, score));
            }
        }

        List<KbChunk> result = scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(Math.max(topK, 1))
                .map(s -> s.chunk().withScore(s.score()))
                .toList();

        // 相对阈值：低于最高分 40% 的算「沾边但不相关」，直接丢掉。
        // 中文 2-gram 很容易因为一个通用词（比如「设备」）把无关文档带进来，
        // 没有这道过滤，topK 里会混进一半噪声。
        if (result.size() > 1) {
            double best = result.get(0).score();
            double floor = best * 0.4;
            result = result.stream().filter(c -> c.score() >= floor).toList();
        }
        return result;
    }

    /** 中文按 2-gram 切，英文/数字按单词切，过滤单字与虚词 */
    static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String s = text.toLowerCase();
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();

        for (char c : s.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FA5) {   // 汉字
                flushWord(word, tokens);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, tokens);
                word.append(c);
            } else {
                flushCjk(cjk, tokens);
                flushWord(word, tokens);
            }
        }
        flushCjk(cjk, tokens);
        flushWord(word, tokens);
        return tokens;
    }

    private static void flushCjk(StringBuilder buf, Set<String> out) {
        String run = buf.toString();
        buf.setLength(0);
        if (run.isEmpty()) {
            return;
        }
        if (run.length() == 1) {
            return;   // 单字噪声太大，丢掉
        }
        for (int i = 0; i < run.length() - 1; i++) {
            out.add(run.substring(i, i + 2));
        }
    }

    private static void flushWord(StringBuilder buf, Set<String> out) {
        String w = buf.toString();
        buf.setLength(0);
        if (w.length() >= 2) {
            out.add(w);
        }
    }

    private record Scored(KbChunk chunk, double score) {
    }

    /** 统计子串出现次数（用于 tf） */
    private static int countOccurrences(String text, String sub) {
        if (text == null || sub == null || sub.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /** 供自测用：打印一个问题的分词结果 */
    public static Set<String> debugTokens(String q) {
        return new HashSet<>(tokenize(q));
    }
}
