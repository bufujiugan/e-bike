package com.bike.server.aftersales;

import com.bike.server.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/after-sales/cases/{caseId}/shipments/{shipmentId}/logistics")
public class AfterSalesLogisticsController {
    private final AfterSalesLogisticsService service;
    public AfterSalesLogisticsController(AfterSalesLogisticsService service){this.service=service;}
    @PostMapping public ApiResponse<AfterSalesShipmentView> act(@PathVariable long caseId,@PathVariable long shipmentId,@RequestBody AfterSalesLogisticsRequest request) {
        return ApiResponse.success("发运信息已更新",service.act(caseId,shipmentId,request));
    }
}
