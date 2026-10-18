package com.cuijian.digital.dao;

import com.cuijian.digital.domain.BomItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class BomDao {

    private static final RowMapper<BomItem> MAPPER = (rs, i) -> new BomItem(
            rs.getString("product_code"),
            rs.getString("material_code"),
            rs.getString("material_name"),
            rs.getString("unit"),
            rs.getBigDecimal("qty_per")
    );

    private final JdbcTemplate jdbc;

    public BomDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 取某产品的完整 BOM，带出物料名和单位，省一次关联查询 */
    public List<BomItem> findByProduct(String productCode) {
        return jdbc.query("""
                SELECT b.product_code, b.material_code, m.name AS material_name, m.unit, b.qty_per
                  FROM bom_item b
                  JOIN material m ON m.code = b.material_code
                 WHERE b.product_code = ?
                 ORDER BY b.material_code
                """, MAPPER, productCode);
    }

    /** 产品是否存在（有没有维护 BOM 是创建工单的前置条件） */
    public boolean hasBom(String productCode) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM bom_item WHERE product_code = ?",
                Long.class, productCode);
        return n != null && n > 0;
    }
}
