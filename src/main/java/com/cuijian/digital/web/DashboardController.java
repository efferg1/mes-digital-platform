package com.cuijian.digital.web;

import com.cuijian.digital.common.ApiResponse;
import com.cuijian.digital.dao.BomDao;
import com.cuijian.digital.dao.InventoryTxnDao;
import com.cuijian.digital.dao.MaterialDao;
import com.cuijian.digital.domain.BomItem;
import com.cuijian.digital.domain.Material;
import com.cuijian.digital.service.IntegrationService;
import com.cuijian.digital.service.WorkOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 看板与基础数据查询 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final WorkOrderService workOrderService;
    private final MaterialDao materialDao;
    private final BomDao bomDao;
    private final InventoryTxnDao txnDao;
    private final IntegrationService integrationService;

    public DashboardController(WorkOrderService workOrderService, MaterialDao materialDao,
                               BomDao bomDao, InventoryTxnDao txnDao,
                               IntegrationService integrationService) {
        this.workOrderService = workOrderService;
        this.materialDao = materialDao;
        this.bomDao = bomDao;
        this.txnDao = txnDao;
        this.integrationService = integrationService;
    }

    /** 首页看板：工单状态分布 + 缺料预警 + 最近库存变动 + 集成统计 */
    @GetMapping("/dashboard/summary")
    public ApiResponse<Map<String, Object>> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workOrderByStatus", workOrderService.statusSummary());
        m.put("belowSafetyStock", materialDao.findBelowSafety());
        m.put("recentInventoryTxn", txnDao.findRecent(10));
        m.put("integrationStats", integrationService.stats());
        return ApiResponse.ok(m);
    }

    @GetMapping("/materials")
    public ApiResponse<List<Material>> materials() {
        return ApiResponse.ok(materialDao.findAll());
    }

    /** 某产品的 BOM，页面里点开产品能看单耗 */
    @GetMapping("/bom/{productCode}")
    public ApiResponse<List<BomItem>> bom(@PathVariable String productCode) {
        return ApiResponse.ok(bomDao.findByProduct(productCode));
    }

    /** 按工单号反查这张单扣了哪些料 —— 账实不符时的第一入口 */
    @GetMapping("/inventory/txn")
    public ApiResponse<List<Map<String, Object>>> txn(@RequestParam String refNo) {
        return ApiResponse.ok(txnDao.findByRefNo(refNo));
    }
}
