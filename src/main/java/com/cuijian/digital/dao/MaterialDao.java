package com.cuijian.digital.dao;

import com.cuijian.digital.domain.Material;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class MaterialDao {

    private static final RowMapper<Material> MAPPER = (rs, i) -> new Material(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("spec"),
            rs.getString("unit"),
            rs.getBigDecimal("stock_qty"),
            rs.getBigDecimal("safety_stock")
    );

    private final JdbcTemplate jdbc;

    public MaterialDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Material> findByCode(String code) {
        List<Material> list = jdbc.query("""
                SELECT id, code, name, spec, unit, stock_qty, safety_stock
                FROM material WHERE code = ?
                """, MAPPER, code);
        return list.stream().findFirst();
    }

    public List<Material> findAll() {
        return jdbc.query("""
                SELECT id, code, name, spec, unit, stock_qty, safety_stock
                FROM material ORDER BY code
                """, MAPPER);
    }

    /** 低于安全库存的物料 */
    public List<Material> findBelowSafety() {
        return jdbc.query("""
                SELECT id, code, name, spec, unit, stock_qty, safety_stock
                FROM material WHERE stock_qty < safety_stock ORDER BY code
                """, MAPPER);
    }

    /**
     * 扣减库存。
     * 关键点：WHERE 里带 stock_qty >= ?，把「够不够扣」的判断交给数据库，
     * 返回 0 表示库存不足。这样并发报工不会扣成负数，也不需要先查后改。
     */
    public int deduct(String code, BigDecimal qty) {
        return jdbc.update("""
                UPDATE material SET stock_qty = stock_qty - ?
                 WHERE code = ? AND stock_qty >= ?
                """, qty, code, qty);
    }

    public BigDecimal stockOf(String code) {
        List<BigDecimal> list = jdbc.queryForList(
                "SELECT stock_qty FROM material WHERE code = ?", BigDecimal.class, code);
        return list.isEmpty() ? BigDecimal.ZERO : list.get(0);
    }
}
