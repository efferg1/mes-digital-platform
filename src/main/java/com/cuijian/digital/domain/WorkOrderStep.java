package com.cuijian.digital.domain;

import java.time.LocalDateTime;

/** 工单工序 */
public record WorkOrderStep(
        Long id,
        Long workOrderId,
        Integer seq,
        String name,
        String status,
        String operator,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
