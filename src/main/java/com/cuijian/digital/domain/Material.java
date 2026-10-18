package com.cuijian.digital.domain;

import java.math.BigDecimal;

/** 物料主数据 */
public record Material(
        Long id,
        String code,
        String name,
        String spec,
        String unit,
        BigDecimal stockQty,
        BigDecimal safetyStock
) {
    /** 低于安全库存要触发补货提醒 */
    public boolean belowSafetyStock() {
        return stockQty != null && safetyStock != null && stockQty.compareTo(safetyStock) < 0;
    }
}
