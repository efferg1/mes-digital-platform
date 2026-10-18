package com.cuijian.digital.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 库存流水。
 * 所有库存变动都必须留一条流水，出问题时能按工单号反查出全部物料去向。
 */
@Repository
public class InventoryTxnDao {

    private final JdbcTemplate jdbc;

    public InventoryTxnDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String materialCode, BigDecimal delta, String txnType,
                       String refNo, BigDecimal balanceAfter) {
        jdbc.update("""
                INSERT INTO inventory_txn (material_code, delta, txn_type, ref_no, balance_after)
                VALUES (?, ?, ?, ?, ?)
                """, materialCode, delta, txnType, refNo, balanceAfter);
    }

    /** 按工单号反查这张单扣了哪些料 */
    public List<Map<String, Object>> findByRefNo(String refNo) {
        return jdbc.queryForList("""
                SELECT t.material_code, m.name AS material_name, t.delta, t.txn_type,
                       t.balance_after, t.created_at
                  FROM inventory_txn t
                  LEFT JOIN material m ON m.code = t.material_code
                 WHERE t.ref_no = ?
                 ORDER BY t.id
                """, refNo);
    }

    public List<Map<String, Object>> findRecent(int limit) {
        return jdbc.queryForList("""
                SELECT t.id, t.material_code, m.name AS material_name, t.delta, t.txn_type,
                       t.ref_no, t.balance_after, t.created_at
                  FROM inventory_txn t
                  LEFT JOIN material m ON m.code = t.material_code
                 ORDER BY t.id DESC
                 LIMIT ?
                """, limit);
    }
}
