package com.bike.server.stock;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
public class StockController {
    private final StockService stockService;
    public StockController(StockService stockService) { this.stockService = stockService; }
    @GetMapping("/api/stock-in/by-no/{stockNo}") public ApiResponse<StockRecordView> getIn(@PathVariable String stockNo) { return ApiResponse.success("查询成功", stockService.getByNo("IN", stockNo)); }
    @GetMapping("/api/stock-out/by-no/{stockNo}") public ApiResponse<StockRecordView> getOut(@PathVariable String stockNo) { return ApiResponse.success("查询成功", stockService.getByNo("OUT", stockNo)); }
    @PostMapping("/api/stock-in/validate") public ApiResponse<StockValidationResult> validateIn(@Valid @RequestBody StockValidationRequest request) { return ApiResponse.success("校验完成", stockService.validateStockIn(request)); }
    @PostMapping("/api/stock-in") public ApiResponse<StockRecordView> stockIn(@Valid @RequestBody StockInRequest request) { return ApiResponse.success("入库成功", stockService.stockIn(request)); }
    @PostMapping("/api/stock-in/{id}/cancel") public ApiResponse<StockRecordView> cancelIn(@PathVariable long id, @RequestParam @NotBlank String reason) { return ApiResponse.success("撤销入库成功", stockService.cancelStockIn(id, reason)); }
    @GetMapping("/api/stock-in") public ApiResponse<PageResponse<StockRecordView>> listIn(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success("查询成功",stockService.list("IN",page,pageSize));}
    @PostMapping("/api/stock-out") public ApiResponse<StockRecordView> stockOut(@Valid @RequestBody StockOutRequest request) { return ApiResponse.success("出库成功", stockService.stockOut(request)); }
    @PostMapping("/api/stock-out/validate") public ApiResponse<StockValidationResult> validateOut(@Valid @RequestBody StockValidationRequest request) { return ApiResponse.success("校验完成", stockService.validateStockOut(request)); }
    @PostMapping("/api/stock-out/{id}/cancel") public ApiResponse<StockRecordView> cancelOut(@PathVariable long id, @RequestParam @NotBlank String reason) { return ApiResponse.success("撤销出库成功", stockService.cancelStockOut(id, reason)); }
    @GetMapping("/api/stock-out") public ApiResponse<PageResponse<StockRecordView>> listOut(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success("查询成功",stockService.list("OUT",page,pageSize));}
    @GetMapping("/api/inventory") public ApiResponse<PageResponse<InventoryRow>> inventory(@RequestParam(required=false) Long modelId,@RequestParam(required=false) Long warehouseId,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success("查询成功",stockService.inventory(modelId,warehouseId,page,pageSize));}
    @GetMapping("/api/inventory/summary") public ApiResponse<java.util.Map<String, Long>> inventorySummary(){return ApiResponse.success("查询成功",stockService.inventorySummary());}
    @GetMapping("/api/inventory/by-model") public ApiResponse<PageResponse<InventoryRow>> inventoryByModel(@RequestParam Long modelId,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success("查询成功",stockService.inventory(modelId,null,page,pageSize));}
}
