package com.cuijian.digital.service;

import com.cuijian.digital.common.BizException;
import com.cuijian.digital.dao.IntegrationLogDao;
import com.cuijian.digital.dao.WorkOrderDao;
import com.cuijian.digital.domain.IntegrationLog;
import com.cuijian.digital.domain.WorkOrder;
import com.cuijian.digital.dto.ErpSalesOrderRequest;
import com.cuijian.digital.dto.TmsShipmentRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 异构系统集成编排。
 *
 * 每个外部系统一个入口方法，流程统一是三步：
 *   1. 幂等占位（失败可重试）
 *   2. 字段映射 + 调业务层
 *   3. 回写状态
 *
 * 字段映射不放在这里，而是各自适配器负责 —— ERP 的 salesOrderNo、
 * TMS 的 shipmentNo 怎么对上内部模型，属于各系统自己的知识。
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

    private final IntegrationLogDao logDao;
    private final WorkOrderDao workOrderDao;
    private final WorkOrderService workOrderService;
    private final ObjectMapper objectMapper;

    public IntegrationService(IntegrationLogDao logDao, WorkOrderDao workOrderDao,
                              WorkOrderService workOrderService, ObjectMapper objectMapper) {
        this.logDao = logDao;
        this.workOrderDao = workOrderDao;
        this.workOrderService = workOrderService;
        this.objectMapper = objectMapper;
    }

    // ==================== ERP 销售订单 ====================

    /**
     * 接收 ERP 销售订单，自动生成生产工单。
     * 幂等键：ERP + SALES_ORDER + salesOrderNo
     */
    public Map<String, Object> receiveErpSalesOrder(ErpSalesOrderRequest req) {
        String bizId = req.salesOrderNo();
        String payload = toJson(req);

        if (!logDao.tryAcquire("ERP", "SALES_ORDER", bizId, "IN", payload)) {
            log.info("ERP 订单 {} 重复推送，已忽略", bizId);
            return Map.of("result", "DUPLICATE",
                    "message", "该销售订单已处理过，未重复建单",
                    "salesOrderNo", bizId);
        }

        try {
            // 第二道校验：工单表里已经有这个来源单号，说明日志表被清过但业务已落库
            Optional<WorkOrder> exists = workOrderDao.findBySource("ERP", bizId);
            if (exists.isPresent()) {
                logDao.markSuccess("ERP", "SALES_ORDER", bizId, "工单已存在，跳过");
                return Map.of("result", "DUPLICATE",
                        "message", "对应工单已存在：" + exists.get().orderNo(),
                        "orderNo", exists.get().orderNo());
            }

            WorkOrder wo = workOrderService.createInternal(
                    req.materialCode(),
                    req.materialName(),
                    req.orderQty(),
                    // ERP 没有优先级字段，按交期远近自动定：交期越近优先级越高
                    priorityByDueDate(req),
                    req.deliveryDate(),
                    "ERP",
                    bizId,
                    req.remark());

            logDao.markSuccess("ERP", "SALES_ORDER", bizId, "已生成工单 " + wo.orderNo());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("result", "SUCCESS");
            m.put("orderNo", wo.orderNo());
            m.put("productCode", wo.productCode());
            m.put("planQty", wo.planQty());
            return m;

        } catch (BizException e) {
            // 业务校验失败：标记 FAILED，允许 ERP 修正数据后重推
            logDao.markFailed("ERP", "SALES_ORDER", bizId, e.getMessage());
            log.warn("ERP 订单 {} 处理失败：{}", bizId, e.getMessage());
            throw e;
        } catch (Exception e) {
            logDao.markFailed("ERP", "SALES_ORDER", bizId, "系统异常：" + e.getMessage());
            log.error("ERP 订单 {} 处理异常", bizId, e);
            throw new BizException(500, "集成处理异常，已记录日志：" + bizId);
        }
    }

    /** 交期越近优先级越高，7 天内为 8，一个月内为 5，其余为 3 */
    private int priorityByDueDate(ErpSalesOrderRequest req) {
        if (req.deliveryDate() == null) {
            return 5;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.now(), req.deliveryDate());
        if (days <= 7) {
            return 8;
        }
        if (days <= 30) {
            return 5;
        }
        return 3;
    }

    // ==================== TMS 运单 ====================

    /**
     * 接收 TMS 回传的运单。
     * 幂等键：TMS + SHIPMENT + shipmentNo
     */
    public Map<String, Object> receiveTmsShipment(TmsShipmentRequest req) {
        String bizId = req.shipmentNo();
        String payload = toJson(req);

        if (!logDao.tryAcquire("TMS", "SHIPMENT", bizId, "IN", payload)) {
            return Map.of("result", "DUPLICATE",
                    "message", "该运单已回传过",
                    "shipmentNo", bizId);
        }

        try {
            WorkOrder wo = workOrderDao.findByOrderNo(req.workOrderNo())
                    .orElseThrow(() -> new BizException("运单关联的工单不存在：" + req.workOrderNo()));

            String msg = String.format("运单 %s 承运商 %s 车牌 %s 预计到货 %s 状态 %s",
                    req.shipmentNo(),
                    req.carrier() == null ? "-" : req.carrier(),
                    req.plateNo() == null ? "-" : req.plateNo(),
                    req.eta() == null ? "-" : req.eta(),
                    req.status() == null ? "IN_TRANSIT" : req.status());

            logDao.markSuccess("TMS", "SHIPMENT", bizId, msg);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("result", "SUCCESS");
            m.put("orderNo", wo.orderNo());
            m.put("shipmentNo", req.shipmentNo());
            m.put("message", msg);
            return m;

        } catch (BizException e) {
            logDao.markFailed("TMS", "SHIPMENT", bizId, e.getMessage());
            throw e;
        } catch (Exception e) {
            logDao.markFailed("TMS", "SHIPMENT", bizId, "系统异常：" + e.getMessage());
            throw new BizException(500, "运单处理异常：" + bizId);
        }
    }

    // ==================== 查询 ====================

    public List<IntegrationLog> recentLogs(int limit) {
        return logDao.findRecent(Math.min(Math.max(limit, 1), 200));
    }

    public List<Map<String, Object>> stats() {
        return logDao.stats();
    }

    private String toJson(Object o) {
        try {
            String s = objectMapper.writeValueAsString(o);
            // payload 字段是 TEXT，留个上限避免异常报文把库撑大
            return s.length() > 4000 ? s.substring(0, 4000) + "..." : s;
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }
}
