package com.cuijian.digital.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/** TMS 回传的运单 */
public record TmsShipmentRequest(
        @NotBlank(message = "运单号不能为空") String shipmentNo,
        @NotBlank(message = "工单号不能为空") String workOrderNo,
        String carrier,
        String plateNo,
        LocalDate eta,
        String status
) {
}
