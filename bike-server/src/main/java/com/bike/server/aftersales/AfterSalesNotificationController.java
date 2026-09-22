package com.bike.server.aftersales;

import com.bike.server.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/after-sales/cases/{caseId}/notifications")
public class AfterSalesNotificationController {
    private final AfterSalesNotificationService service;
    public AfterSalesNotificationController(AfterSalesNotificationService service){this.service=service;}
    @GetMapping public ApiResponse<AfterSalesNotificationService.Page> list(@PathVariable long caseId,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success("查询成功",service.list(caseId,page,pageSize));}
    @PostMapping("/{id}/retry") public ApiResponse<AfterSalesNotificationService.View> retry(@PathVariable long caseId,@PathVariable long id,@RequestBody AfterSalesNotificationService.Retry request){return ApiResponse.success("通知已重新排队",service.retry(caseId,id,request));}
}
