package com.bike.server.europe;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/europe")
public class EuropeSalesController {
    private final EuropeSalesService service;
    public EuropeSalesController(EuropeSalesService service) { this.service=service; }
    public record StepRequest(@Positive int revision, boolean completed, @Size(max=4000) String note) {}
    public record ReversalRequest(@NotBlank @Size(max=500) String reason) {}
    @GetMapping("/factory-orders") public ApiResponse<PageResponse<FactorySalesView>> list(
            @RequestParam(required=false) String keyword,@RequestParam(required=false) Long warehouseId,
            @RequestParam(required=false) String businessType,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize) {
        return ApiResponse.success("查询成功",service.listFactoryOrders(keyword,warehouseId,businessType,page,pageSize));
    }
    @GetMapping("/factory-orders/{id}") public ApiResponse<FactorySalesView> get(@PathVariable long id) { return ApiResponse.success("查询成功",service.getFactoryOrder(id)); }
    @PostMapping("/factory-orders") public ApiResponse<FactorySalesView> create(@Valid @RequestBody FactorySalesRequest request) { return ApiResponse.success("已建立工厂直签订单",service.createFactoryOrder(request)); }
    @PutMapping("/factory-orders/{id}") public ApiResponse<FactorySalesView> update(@PathVariable long id,@Valid @RequestBody FactorySalesRequest request) { return ApiResponse.success("已保存",service.updateFactoryOrder(id,request)); }
    @PostMapping("/factory-orders/{id}/steps/{step}") public ApiResponse<FactorySalesView> step(@PathVariable long id,@PathVariable String step,@Valid @RequestBody StepRequest request) { return ApiResponse.success("流程已更新",service.confirmFactoryStep(id,step,request.revision(),request.completed(),request.note())); }
    @GetMapping("/settlements/{type}/{subjectId}") public ApiResponse<List<SalesSettlementView>> settlements(@PathVariable String type,@PathVariable long subjectId) { return ApiResponse.success("查询成功",service.listSettlements(type,subjectId)); }
    @PostMapping("/settlements/{type}/{subjectId}") public ApiResponse<List<SalesSettlementView>> record(@PathVariable String type,@PathVariable long subjectId,@Valid @RequestBody SalesSettlementRequest request) { return ApiResponse.success("已登记实际款项",service.recordSettlement(type,subjectId,request)); }
    @PutMapping("/settlements/{type}/{subjectId}/{entryId}/supplement") public ApiResponse<List<SalesSettlementView>> supplement(@PathVariable String type,@PathVariable long subjectId,@PathVariable long entryId,@Valid @RequestBody SalesSettlementSupplement request) { return ApiResponse.success("参考信息已补录",service.supplementSettlement(type,subjectId,entryId,request)); }
    @PostMapping("/settlements/{type}/{subjectId}/{entryId}/reverse") public ApiResponse<List<SalesSettlementView>> reverse(@PathVariable String type,@PathVariable long subjectId,@PathVariable long entryId,@Valid @RequestBody ReversalRequest request) { return ApiResponse.success("款项记录已撤回",service.reverseSettlement(type,subjectId,entryId,request.reason())); }
}
