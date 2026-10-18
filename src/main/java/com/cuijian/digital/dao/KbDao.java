package com.cuijian.digital.dao;

import com.cuijian.digital.domain.KbChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class KbDao {

    private static final RowMapper<KbChunk> MAPPER = (rs, i) -> new KbChunk(
            rs.getLong("id"),
            rs.getLong("doc_id"),
            rs.getString("doc_title"),
            rs.getInt("seq"),
            rs.getString("content"),
            0.0,
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public KbDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findAllDocuments() {
        return jdbc.queryForList("""
                SELECT d.id, d.title, d.category, d.source, LENGTH(d.content) AS content_len,
                       (SELECT COUNT(*) FROM kb_chunk c WHERE c.doc_id = d.id) AS chunk_count
                  FROM kb_document d ORDER BY d.id
                """);
    }

    /** 取出全部切片用于内存检索（演示规模够用；上万条时应换成向量库） */
    public List<KbChunk> findAllChunks() {
        return jdbc.query("""
                SELECT c.id, c.doc_id, d.title AS doc_title, c.seq, c.content, c.created_at
                  FROM kb_chunk c
                  JOIN kb_document d ON d.id = c.doc_id
                 ORDER BY c.doc_id, c.seq
                """, MAPPER);
    }

    public void deleteAllChunks() {
        jdbc.update("DELETE FROM kb_chunk");
    }

    public void insertChunk(long docId, int seq, String content) {
        jdbc.update("INSERT INTO kb_chunk (doc_id, seq, content) VALUES (?, ?, ?)", docId, seq, content);
    }

    public long countChunks() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM kb_chunk", Long.class);
        return n == null ? 0 : n;
    }

    public long countDocuments() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document", Long.class);
        return n == null ? 0 : n;
    }

    /** 文档原文按 id 取，切片时用 */
    public List<Map<String, Object>> findAllRawDocuments() {
        return jdbc.queryForList("SELECT id, title, content FROM kb_document ORDER BY id");
    }
}
