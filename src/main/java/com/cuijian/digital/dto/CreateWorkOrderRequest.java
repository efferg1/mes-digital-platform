package com.cuijian.digital.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 手工创建工单的入参 */
public record CreateWorkOrderRequest(
        @NotBlank(message = "产品编码不能为空")
        String productCode,

        @NotNull(message = "计划数量不能为空")
        @DecimalMin(value = "0.01", message = "计划数量必须大于 0")
        BigDecimal planQty,

        @jakarta.validation.constraints.Min(value = 1, message = "优先级取 1-9")
        @jakarta.validation.constraints.Max(value = 9, message = "优先级取 1-9")
        Integer priority,

        LocalDate dueDate,

        String remark
) {
}
