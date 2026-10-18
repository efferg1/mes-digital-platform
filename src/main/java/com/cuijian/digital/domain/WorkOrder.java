package com.cuijian.digital.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 生产工单。
 * status 取值见 {@link WorkOrderStatus}。
 */
public record WorkOrder(
        Long id,
        String orderNo,
        String productCode,
        String productName,
        BigDecimal planQty,
        BigDecimal doneQty,
        String status,
        Integer priority,
        LocalDate dueDate,
        String sourceSystem,
        String sourceOrderNo,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /** 已完成数量占比，用于前端进度条 */
    public int progress() {
        if (planQty == null || planQty.signum() == 0 || doneQty == null) {
            return 0;
        }
        return doneQty.multiply(BigDecimal.valueOf(100))
                .divide(planQty, 0, java.math.RoundingMode.DOWN)
                .intValue();
    }
}
