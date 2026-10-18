package com.cuijian.digital.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** 报工入参 */
public record ReportRequest(
        @NotNull(message = "报工数量不能为空")
        @DecimalMin(value = "0.01", message = "报工数量必须大于 0")
        BigDecimal qty,

        @NotBlank(message = "报工人不能为空")
        String operator
) {
}
