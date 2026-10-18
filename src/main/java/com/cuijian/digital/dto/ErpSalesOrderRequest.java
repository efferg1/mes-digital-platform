package com.cuijian.digital.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ERP 推送过来的销售订单。
 * 字段名刻意和平台内部模型不一致（salesOrderNo vs sourceOrderNo），
 * 映射逻辑集中在适配器里，不污染业务层。
 */
public record ErpSalesOrderRequest(
        @NotBlank(message = "销售订单号不能为空") String salesOrderNo,
        @NotBlank(message = "物料编码不能为空") String materialCode,
        String materialName,
        @NotNull(message = "订单数量不能为空")
        @DecimalMin(value = "0.01", message = "订单数量必须大于 0") BigDecimal orderQty,
        LocalDate deliveryDate,
        String customerCode,
        String remark
) {
}
