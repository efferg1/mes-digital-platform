package com.cuijian.digital.domain;

import java.time.LocalDateTime;

/**
 * 集成日志。外部系统每进来一次就记一条，
 * (system, biz_type, biz_id) 唯一索引同时承担幂等职责。
 */
public record IntegrationLog(
        Long id,
        String system,
        String bizType,
        String bizId,
        String direction,
        String status,
        String payload,
        String message,
        LocalDateTime createdAt
) {
}
