package com.cuijian.digital.domain;

import java.math.BigDecimal;

/** BOM 单耗：生产 1 个成品需要多少该物料 */
public record BomItem(
        String productCode,
        String materialCode,
        String materialName,
        String unit,
        BigDecimal qtyPer
) {
}
