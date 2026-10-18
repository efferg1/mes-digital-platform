package com.cuijian.digital.web;

import com.cuijian.digital.common.ApiResponse;
import com.cuijian.digital.domain.WorkOrder;
import com.cuijian.digital.dto.CreateWorkOrderRequest;
import com.cuijian.digital.dto.PageResult;
import com.cuijian.digital.dto.ReportRequest;
import com.cuijian.digital.service.WorkOrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) {
        this.service = service;
    }

    /** 分页列表，可按状态过滤 */
    @GetMapping
    public ApiResponse<PageResult<WorkOrder>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.page(status, page, size));
    }

    /** 工单详情：含工序、BOM、已耗物料 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    /** 手工创建工单 */
    @PostMapping
    public ApiResponse<WorkOrder> create(@Valid @RequestBody CreateWorkOrderRequest req) {
        return ApiResponse.ok("工单创建成功", service.create(req));
    }

    /** 下达：待下达 -> 已下达，同时开工第一道工序 */
    @PostMapping("/{id}/release")
    public ApiResponse<WorkOrder> release(@PathVariable long id) {
        return ApiResponse.ok("已下达", service.release(id));
    }

    /**
     * 报工：扣减库存 + 写流水 + 推进工序，全部在一个事务里。
     * 库存不足会整单回滚，返回具体的缺料信息。
     */
    @PostMapping("/{id}/report")
    public ApiResponse<Map<String, Object>> report(@PathVariable long id,
                                                   @Valid @RequestBody ReportRequest req) {
        return ApiResponse.ok("报工成功", service.report(id, req));
    }
}
