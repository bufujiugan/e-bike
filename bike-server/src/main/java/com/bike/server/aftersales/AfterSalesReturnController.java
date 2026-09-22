package com.bike.server.aftersales;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/after-sales/cases/{caseId}/returns")
public class AfterSalesReturnController {
    private final AfterSalesReturnService service;
    public AfterSalesReturnController(AfterSalesReturnService service) { this.service=service; }
    @GetMapping public ApiResponse<List<AfterSalesReturnView>> list(@PathVariable long caseId) {
        return ApiResponse.success("查询成功",service.list(caseId));
    }
    @PostMapping("/{shipmentId}") public ApiResponse<AfterSalesReturnView> act(@PathVariable long caseId,@PathVariable long shipmentId,@Valid @RequestBody AfterSalesReturnRequest request) {
        return ApiResponse.success("旧件状态已更新",service.act(caseId,shipmentId,request));
    }
}
