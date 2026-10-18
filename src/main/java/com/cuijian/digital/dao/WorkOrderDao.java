package com.cuijian.digital.dao;

import com.cuijian.digital.domain.WorkOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class WorkOrderDao {

    private static final String SELECT_BASE = """
            SELECT id, order_no, product_code, product_name, plan_qty, done_qty, status,
                   priority, due_date, source_system, source_order_no, remark, created_at, updated_at
            FROM work_order
            """;

    private static final RowMapper<WorkOrder> MAPPER = (rs, i) -> new WorkOrder(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getString("product_code"),
            rs.getString("product_name"),
            rs.getBigDecimal("plan_qty"),
            rs.getBigDecimal("done_qty"),
            rs.getString("status"),
            rs.getInt("priority"),
            rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
            rs.getString("source_system"),
            rs.getString("source_order_no"),
            rs.getString("remark"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public WorkOrderDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WorkOrder> findById(long id) {
        List<WorkOrder> list = jdbc.query(SELECT_BASE + "WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<WorkOrder> findByOrderNo(String orderNo) {
        List<WorkOrder> list = jdbc.query(SELECT_BASE + "WHERE order_no = ?", MAPPER, orderNo);
        return list.stream().findFirst();
    }

    /** 外部单号反查，用于集成的幂等判断 */
    public Optional<WorkOrder> findBySource(String system, String sourceOrderNo) {
        List<WorkOrder> list = jdbc.query(
                SELECT_BASE + "WHERE source_system = ? AND source_order_no = ?",
                MAPPER, system, sourceOrderNo);
        return list.stream().findFirst();
    }

    /**
     * 分页查询。
     * 走 idx_wo_status_created 联合索引：status 等值 + created_at 排序，
     * 数据量上来后不会退化成全表扫 + filesort。
     */
    public List<WorkOrder> findPage(String status, int offset, int size) {
        if (status == null || status.isBlank()) {
            return jdbc.query(SELECT_BASE + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                    MAPPER, size, offset);
        }
        return jdbc.query(SELECT_BASE + "WHERE status = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                MAPPER, status, size, offset);
    }

    public long count(String status) {
        Long n;
        if (status == null || status.isBlank()) {
            n = jdbc.queryForObject("SELECT COUNT(*) FROM work_order", Long.class);
        } else {
            n = jdbc.queryForObject("SELECT COUNT(*) FROM work_order WHERE status = ?", Long.class, status);
        }
        return n == null ? 0 : n;
    }

    /** 各状态数量，看板用 */
    public List<java.util.Map<String, Object>> countByStatus() {
        return jdbc.queryForList("SELECT status, COUNT(*) AS cnt FROM work_order GROUP BY status ORDER BY cnt DESC");
    }

    public long insert(String orderNo, String productCode, String productName, java.math.BigDecimal planQty,
                       int priority, java.time.LocalDate dueDate, String sourceSystem,
                       String sourceOrderNo, String remark) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            // 显式指定只回传 id 列：
            // H2 在 MySQL 兼容模式下会把带 DEFAULT 的列（created_at/updated_at）也一并返回，
            // 那样 getKey() 会因为「多列」直接抛异常。
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO work_order
                        (order_no, product_code, product_name, plan_qty, done_qty, status,
                         priority, due_date, source_system, source_order_no, remark)
                    VALUES (?, ?, ?, ?, 0, 'CREATED', ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, orderNo);
            ps.setString(2, productCode);
            ps.setString(3, productName);
            ps.setBigDecimal(4, planQty);
            ps.setInt(5, priority);
            if (dueDate == null) {
                ps.setNull(6, java.sql.Types.DATE);
            } else {
                ps.setDate(6, java.sql.Date.valueOf(dueDate));
            }
            ps.setString(7, sourceSystem);
            ps.setString(8, sourceOrderNo);
            ps.setString(9, remark);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1 : key.longValue();
    }

    public int updateStatus(long id, String status) {
        return jdbc.update("UPDATE work_order SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, id);
    }

    /** 报工：数量累加 + 状态推进，一条 SQL 完成，避免读-改-写之间的并发覆盖 */
    public int addDoneQty(long id, java.math.BigDecimal qty, String newStatus) {
        return jdbc.update("""
                UPDATE work_order
                   SET done_qty = done_qty + ?,
                       status = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, qty, newStatus, id);
    }

    /** 判断某个产品是否已经存在工单（创建时校验产品编码是否有效会用到） */
    public long countByProduct(String productCode) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM work_order WHERE product_code = ?",
                Long.class, productCode);
        return n == null ? 0 : n;
    }

    /** 当天已建单数，用于生成 WO-yyyyMMdd-序号 */
    public long countByOrderNoPrefix(String prefix) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM work_order WHERE order_no LIKE ?",
                Long.class, prefix + "%");
        return n == null ? 0 : n;
    }
}
