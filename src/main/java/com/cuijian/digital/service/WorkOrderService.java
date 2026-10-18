package com.cuijian.digital.service;

import com.cuijian.digital.common.BizException;
import com.cuijian.digital.dao.BomDao;
import com.cuijian.digital.dao.InventoryTxnDao;
import com.cuijian.digital.dao.MaterialDao;
import com.cuijian.digital.dao.WorkOrderDao;
import com.cuijian.digital.dao.WorkOrderStepDao;
import com.cuijian.digital.domain.BomItem;
import com.cuijian.digital.domain.WorkOrder;
import com.cuijian.digital.domain.WorkOrderStatus;
import com.cuijian.digital.domain.WorkOrderStep;
import com.cuijian.digital.dto.CreateWorkOrderRequest;
import com.cuijian.digital.dto.PageResult;
import com.cuijian.digital.dto.ReportRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);

    /** 默认工艺路线，新工单按这个生成工序 */
    private static final String DEFAULT_STEPS = "备料,成型,检验包装";

    private final WorkOrderDao workOrderDao;
    private final WorkOrderStepDao stepDao;
    private final BomDao bomDao;
    private final MaterialDao materialDao;
    private final InventoryTxnDao txnDao;

    public WorkOrderService(WorkOrderDao workOrderDao, WorkOrderStepDao stepDao, BomDao bomDao,
                            MaterialDao materialDao, InventoryTxnDao txnDao) {
        this.workOrderDao = workOrderDao;
        this.stepDao = stepDao;
        this.bomDao = bomDao;
        this.materialDao = materialDao;
        this.txnDao = txnDao;
    }

    // ==================== 创建 ====================

    @Transactional
    public WorkOrder create(CreateWorkOrderRequest req) {
        return createInternal(req.productCode(), null, req.planQty(),
                req.priority() == null ? 5 : req.priority(),
                req.dueDate(), "MANUAL", null, req.remark());
    }

    @Transactional
    public WorkOrder createInternal(String productCode, String productName, BigDecimal planQty,
                                    int priority, LocalDate dueDate, String sourceSystem,
                                    String sourceOrderNo, String remark) {
        // 没有维护 BOM 的产品不允许投产，否则报工时无法计算物料消耗
        if (!bomDao.hasBom(productCode)) {
            throw new BizException("产品 " + productCode + " 未维护 BOM，无法投产");
        }

        String orderNo = nextOrderNo();
        long id = workOrderDao.insert(orderNo, productCode, productName,
                planQty, priority, dueDate, sourceSystem, sourceOrderNo, remark);
        stepDao.insertDefaultSteps(id, DEFAULT_STEPS);
        log.info("创建工单 {} 产品 {} 数量 {} 来源 {}", orderNo, productCode, planQty, sourceSystem);
        return workOrderDao.findById(id).orElseThrow();
    }

    /** 工单号：WO-yyyyMMdd-四位序号（按当天已建单数递增） */
    private String nextOrderNo() {
        String prefix = "WO-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        long today = workOrderDao.countByOrderNoPrefix(prefix);
        return prefix + String.format("%04d", today + 1);
    }

    // ==================== 下达 ====================

    @Transactional
    public WorkOrder release(long id) {
        WorkOrder wo = getOrThrow(id);
        if (!WorkOrderStatus.releasable(wo.status())) {
            throw new BizException("只有待下达（CREATED）的工单可以下达，当前状态：" + wo.status());
        }
        // 下达即开工第一道工序
        stepDao.completeCurrentAndStartNext(id, null);
        workOrderDao.updateStatus(id, WorkOrderStatus.RELEASED);
        return getOrThrow(id);
    }

    // ==================== 报工（核心事务） ====================

    /**
     * 报工。
     *
     * 一个事务内完成四件事，任何一步失败全部回滚：
     *   1. 校验状态与数量（不能超产）
     *   2. 按 BOM 逐料扣减库存（库存不足直接抛异常回滚，不会扣一半）
     *   3. 写库存流水，记录扣减后余额
     *   4. 推进工单状态与工序
     *
     * 之所以把扣料放在事务里而不是事后异步补，是因为「报了工却没扣料」
     * 会让账实不符，事后核对成本远高于一次事务的开销。
     */
    @Transactional
    public Map<String, Object> report(long id, ReportRequest req) {
        WorkOrder wo = getOrThrow(id);

        if (!WorkOrderStatus.reportable(wo.status())) {
            throw new BizException("当前状态 " + wo.status() + " 不允许报工，需先下达");
        }

        BigDecimal newDone = wo.doneQty().add(req.qty());
        if (newDone.compareTo(wo.planQty()) > 0) {
            throw new BizException(String.format("报工后累计 %.2f 将超过计划量 %.2f，请核对数量",
                    newDone, wo.planQty()));
        }

        List<BomItem> bom = bomDao.findByProduct(wo.productCode());
        if (bom.isEmpty()) {
            throw new BizException("产品 " + wo.productCode() + " 的 BOM 为空，无法扣料");
        }

        List<Map<String, Object>> consumed = new ArrayList<>();
        for (BomItem item : bom) {
            // 单耗 × 报工数量，保留 2 位
            BigDecimal need = item.qtyPer().multiply(req.qty()).setScale(2, RoundingMode.HALF_UP);

            int rows = materialDao.deduct(item.materialCode(), need);
            if (rows == 0) {
                BigDecimal stock = materialDao.stockOf(item.materialCode());
                // 抛异常 -> Spring 回滚整个事务，前面已经扣掉的料会还原
                throw new BizException(String.format(
                        "物料 %s（%s）库存不足：需要 %.2f %s，当前仅剩 %.2f",
                        item.materialName(), item.materialCode(), need, item.unit(), stock));
            }

            BigDecimal balance = materialDao.stockOf(item.materialCode());
            txnDao.insert(item.materialCode(), need.negate(), "ISSUE", wo.orderNo(), balance);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialCode", item.materialCode());
            row.put("materialName", item.materialName());
            row.put("qty", need);
            row.put("unit", item.unit());
            row.put("balanceAfter", balance);
            consumed.add(row);
        }

        boolean finished = newDone.compareTo(wo.planQty()) == 0;
        String newStatus = finished ? WorkOrderStatus.FINISHED : WorkOrderStatus.IN_PROGRESS;

        workOrderDao.addDoneQty(id, req.qty(), newStatus);
        stepDao.completeCurrentAndStartNext(id, req.operator());

        log.info("报工 {} +{} 累计 {}/{} 状态 {} 耗料 {} 项",
                wo.orderNo(), req.qty(), newDone, wo.planQty(), newStatus, consumed.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", wo.orderNo());
        result.put("reportedQty", req.qty());
        result.put("doneQty", newDone);
        result.put("planQty", wo.planQty());
        result.put("status", newStatus);
        result.put("operator", req.operator());
        result.put("consumed", consumed);
        return result;
    }

    // ==================== 查询 ====================

    public WorkOrder getOrThrow(long id) {
        return workOrderDao.findById(id)
                .orElseThrow(() -> new BizException(404, "工单不存在：" + id));
    }

    public PageResult<WorkOrder> page(String status, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        List<WorkOrder> list = workOrderDao.findPage(status, (p - 1) * s, s);
        long total = workOrderDao.count(status);
        return PageResult.of(list, total, p, s);
    }

    /** 工单详情：主体 + 工序 + 已耗物料 */
    public Map<String, Object> detail(long id) {
        WorkOrder wo = getOrThrow(id);
        List<WorkOrderStep> steps = stepDao.findByWorkOrder(id);
        List<BomItem> bom = bomDao.findByProduct(wo.productCode());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", wo.orderNo());
        m.put("productCode", wo.productCode());
        m.put("productName", wo.productName());
        m.put("planQty", wo.planQty());
        m.put("doneQty", wo.doneQty());
        m.put("progress", wo.progress());
        m.put("status", wo.status());
        m.put("priority", wo.priority());
        m.put("dueDate", wo.dueDate());
        m.put("sourceSystem", wo.sourceSystem());
        m.put("sourceOrderNo", wo.sourceOrderNo());
        m.put("remark", wo.remark());
        m.put("createdAt", wo.createdAt());
        m.put("steps", steps);
        m.put("bom", bom);
        m.put("consumedTotal", txnDao.findByRefNo(wo.orderNo()));
        return m;
    }

    public List<Map<String, Object>> statusSummary() {
        return workOrderDao.countByStatus();
    }
}
