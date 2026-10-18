package com.cuijian.digital.dao;

import com.cuijian.digital.domain.IntegrationLog;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class IntegrationLogDao {

    private static final RowMapper<IntegrationLog> MAPPER = (rs, i) -> new IntegrationLog(
            rs.getLong("id"),
            rs.getString("system"),
            rs.getString("biz_type"),
            rs.getString("biz_id"),
            rs.getString("direction"),
            rs.getString("status"),
            rs.getString("payload"),
            rs.getString("message"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public IntegrationLogDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 抢占式幂等：
     * 直接 INSERT，靠 (system, biz_type, biz_id) 唯一索引挡重复。
     *
     * 返回 true  = 可以继续处理；
     * 返回 false = 这条报文之前已成功处理过，判定为重复推送。
     *
     * 特别处理 FAILED：上一次处理失败了，允许对方重推 ——
     * 删掉旧记录再插一条，否则唯一索引会把正常重试也挡在外面。
     */
    public boolean tryAcquire(String system, String bizType, String bizId,
                              String direction, String payload) {
        try {
            doInsert(system, bizType, bizId, direction, payload);
            return true;
        } catch (DuplicateKeyException e) {
            String status = findStatus(system, bizType, bizId);
            if ("FAILED".equals(status)) {
                deleteForRetry(system, bizType, bizId);
                doInsert(system, bizType, bizId, direction, payload);
                return true;
            }
            return false;
        }
    }

    private void doInsert(String system, String bizType, String bizId,
                          String direction, String payload) {
        jdbc.update("""
                INSERT INTO integration_log (system, biz_type, biz_id, direction, status, payload, message)
                VALUES (?, ?, ?, ?, 'PROCESSING', ?, NULL)
                """, system, bizType, bizId, direction, payload);
    }

    public String findStatus(String system, String bizType, String bizId) {
        List<String> list = jdbc.queryForList("""
                SELECT status FROM integration_log
                 WHERE system = ? AND biz_type = ? AND biz_id = ?
                """, String.class, system, bizType, bizId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void markSuccess(String system, String bizType, String bizId, String message) {
        jdbc.update("""
                UPDATE integration_log SET status = 'SUCCESS', message = ?
                 WHERE system = ? AND biz_type = ? AND biz_id = ?
                """, message, system, bizType, bizId);
    }

    public void markFailed(String system, String bizType, String bizId, String message) {
        jdbc.update("""
                UPDATE integration_log SET status = 'FAILED', message = ?
                 WHERE system = ? AND biz_type = ? AND biz_id = ?
                """, message, system, bizType, bizId);
    }

    /** 处理失败时把记录删掉，允许对方重推（否则唯一索引会挡住重试） */
    public void deleteForRetry(String system, String bizType, String bizId) {
        jdbc.update("DELETE FROM integration_log WHERE system = ? AND biz_type = ? AND biz_id = ?",
                system, bizType, bizId);
    }

    public List<IntegrationLog> findRecent(int limit) {
        return jdbc.query("""
                SELECT id, system, biz_type, biz_id, direction, status, payload, message, created_at
                FROM integration_log ORDER BY id DESC LIMIT ?
                """, MAPPER, limit);
    }

    public List<java.util.Map<String, Object>> stats() {
        return jdbc.queryForList("""
                SELECT system, status, COUNT(*) AS cnt
                  FROM integration_log GROUP BY system, status ORDER BY system
                """);
    }
}
