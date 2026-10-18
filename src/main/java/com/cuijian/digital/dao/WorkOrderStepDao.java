package com.cuijian.digital.dao;

import com.cuijian.digital.domain.WorkOrderStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class WorkOrderStepDao {

    private static final RowMapper<WorkOrderStep> MAPPER = (rs, i) -> new WorkOrderStep(
            rs.getLong("id"),
            rs.getLong("work_order_id"),
            rs.getInt("seq"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getString("operator"),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public WorkOrderStepDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<WorkOrderStep> findByWorkOrder(long workOrderId) {
        return jdbc.query("""
                SELECT id, work_order_id, seq, name, status, operator, started_at, finished_at
                FROM work_order_step WHERE work_order_id = ? ORDER BY seq
                """, MAPPER, workOrderId);
    }

    /** 新建工单时按固定工艺路线生成工序 */
    public void insertDefaultSteps(long workOrderId, String stepNames) {
        String[] names = stepNames.split(",");
        for (int i = 0; i < names.length; i++) {
            jdbc.update("""
                    INSERT INTO work_order_step (work_order_id, seq, name, status)
                    VALUES (?, ?, ?, 'PENDING')
                    """, workOrderId, (i + 1) * 10, names[i].trim());
        }
    }

    /** 下行工序完成：把当前 RUNNING 的工序置为 DONE，下一道置为 RUNNING */
    public void completeCurrentAndStartNext(long workOrderId, String operator) {
        jdbc.update("""
                UPDATE work_order_step
                   SET status = 'DONE', finished_at = CURRENT_TIMESTAMP,
                       operator = COALESCE(operator, ?)
                 WHERE work_order_id = ? AND status = 'RUNNING'
                """, operator, workOrderId);

        // 先查出下一道工序的 id 再更新：部分数据库不支持在 UPDATE 里直接嵌带 LIMIT 的子查询
        List<Long> next = jdbc.queryForList("""
                SELECT id FROM work_order_step
                 WHERE work_order_id = ? AND status = 'PENDING'
                 ORDER BY seq LIMIT 1
                """, Long.class, workOrderId);
        if (!next.isEmpty()) {
            jdbc.update("""
                    UPDATE work_order_step
                       SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP, operator = ?
                     WHERE id = ?
                    """, operator, next.get(0));
        }
    }
}
