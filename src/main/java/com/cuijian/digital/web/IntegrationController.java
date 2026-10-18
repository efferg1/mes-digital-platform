package com.cuijian.digital.web;

import com.cuijian.digital.common.ApiResponse;
import com.cuijian.digital.domain.IntegrationLog;
import com.cuijian.digital.dto.ErpSalesOrderRequest;
import com.cuijian.digital.dto.TmsShipmentRequest;
import com.cuijian.digital.service.IntegrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 外部系统接入入口。
 * 真实项目中这些接口要加签名校验和 IP 白名单，这里为了演示完整性省略。
 */
@RestController
@RequestMapping("/api/integration")
public class IntegrationController {

    private final IntegrationService service;

    public IntegrationController(IntegrationService service) {
        this.service = service;
    }

    /** ERP 推送销售订单 -> 自动建生产工单（幂等） */
    @PostMapping("/erp/sales-orders")
    public ApiResponse<Map<String, Object>> erpSalesOrder(@Valid @RequestBody ErpSalesOrderRequest req) {
        return ApiResponse.ok(service.receiveErpSalesOrder(req));
    }

    /** TMS 回传运单（幂等） */
    @PostMapping("/tms/shipments")
    public ApiResponse<Map<String, Object>> tmsShipment(@Valid @RequestBody TmsShipmentRequest req) {
        return ApiResponse.ok(service.receiveTmsShipment(req));
    }

    /** 最近的集成日志，排查通信用 */
    @GetMapping("/logs")
    public ApiResponse<List<IntegrationLog>> logs(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.recentLogs(limit));
    }

    /** 各系统成功/失败数量统计 */
    @GetMapping("/stats")
    public ApiResponse<List<Map<String, Object>>> stats() {
        return ApiResponse.ok(service.stats());
    }
}
