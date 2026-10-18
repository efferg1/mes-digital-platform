package com.cuijian.digital.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档切片。
 *
 * 切分的粒度直接决定检索质量：
 *   - 切太碎：一句话一个片段，模型拿不到上下文，答非所问
 *   - 切太大：一个片段塞进多个主题，检索命中后噪声多，还白白占 token
 *
 * 这里的策略：优先按中文句子（。；）断开，累积到阈值再切，
 * 同时保留上一段的末尾做重叠，避免答案正好落在切口上被"腰斩"。
 */
public final class TextChunker {

    /** 单片段目标长度（字符） */
    private static final int TARGET = 220;
    /** 片段上限，超过强制断开 */
    private static final int MAX = 320;
    /** 相邻片段重叠字符数 */
    private static final int OVERLAP = 40;

    private TextChunker() {
    }

    public static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // 先按句末标点切成句子，保留标点
        List<String> sentences = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (char c : text.trim().toCharArray()) {
            buf.append(c);
            if (c == '。' || c == '；' || c == '！' || c == '？' || c == '\n') {
                String s = buf.toString().trim();
                if (!s.isEmpty()) {
                    sentences.add(s);
                }
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            sentences.add(buf.toString().trim());
        }

        StringBuilder current = new StringBuilder();
        for (String s : sentences) {
            // 单句超长，先把它自己拆开
            if (s.length() > MAX) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                for (int i = 0; i < s.length(); i += MAX - OVERLAP) {
                    chunks.add(s.substring(i, Math.min(i + MAX, s.length())));
                    if (i + MAX >= s.length()) {
                        break;
                    }
                }
                continue;
            }

            if (current.length() + s.length() > TARGET && current.length() > 0) {
                chunks.add(current.toString());
                // 用上一段的尾巴做重叠，保证跨段的语义不被切断
                String tail = current.length() > OVERLAP
                        ? current.substring(current.length() - OVERLAP)
                        : current.toString();
                current.setLength(0);
                current.append(tail);
            }
            current.append(s);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }
}
