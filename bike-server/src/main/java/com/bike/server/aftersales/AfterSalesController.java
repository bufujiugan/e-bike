package com.bike.server.aftersales;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/after-sales/cases")
public class AfterSalesController {
    private final AfterSalesService service;
    public AfterSalesController(AfterSalesService service){this.service=service;}
    @GetMapping("/options") public ApiResponse<java.util.Map<String, List<AfterSalesReferenceOption>>> options(){return ApiResponse.success("查询成功",service.options());}
    @GetMapping public ApiResponse<PageResponse<AfterSalesCaseView>> list(@ModelAttribute AfterSalesCaseFilter filter,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success("查询成功",service.list(filter,page,pageSize));}
    @GetMapping("/dashboard") public ApiResponse<AfterSalesDashboardView> dashboard(@ModelAttribute AfterSalesCaseFilter filter){return ApiResponse.success("查询成功",service.dashboard(filter));}
    @GetMapping("/{id}") public ApiResponse<AfterSalesCaseView> get(@PathVariable long id){return ApiResponse.success("查询成功",service.get(id));}
    @PostMapping public ApiResponse<AfterSalesCaseView> create(@Valid @RequestBody AfterSalesCaseCreateRequest request){return ApiResponse.success("草稿已创建",service.create(request));}
    @PutMapping("/{id}") public ApiResponse<AfterSalesCaseView> update(@PathVariable long id,@Valid @RequestBody AfterSalesCaseCreateRequest request){return ApiResponse.success("案例已更新",service.update(id,request));}
    @PostMapping("/{id}/submit") public ApiResponse<AfterSalesCaseView> submit(@PathVariable long id){return ApiResponse.success("案例已提交",service.submit(id));}
    @PostMapping("/{id}/review") public ApiResponse<AfterSalesCaseView> review(@PathVariable long id,@Valid @RequestBody AfterSalesReviewRequest request){return ApiResponse.success("审核完成",service.review(id,request));}
    @PostMapping("/{id}/solution") public ApiResponse<AfterSalesCaseView> solution(@PathVariable long id,@Valid @RequestBody AfterSalesSolutionRequest request){return ApiResponse.success("技术方案已保存",service.saveSolution(id,request));}
    @PostMapping("/{id}/process") public ApiResponse<AfterSalesCaseView> process(@PathVariable long id){return ApiResponse.success("案例已开始处理",service.startProcessing(id));}
    @GetMapping("/{id}/messages") public ApiResponse<List<AfterSalesMessageView>> messages(@PathVariable long id){return ApiResponse.success("查询成功",service.messages(id));}
    @PostMapping("/{id}/messages") public ApiResponse<AfterSalesMessageView> message(@PathVariable long id,@Valid @RequestBody AfterSalesMessageRequest request){return ApiResponse.success("留言已发送",service.addMessage(id,request));}
    @GetMapping("/{id}/shipments") public ApiResponse<List<AfterSalesShipmentView>> shipments(@PathVariable long id){return ApiResponse.success("查询成功",service.shipments(id));}
    @PostMapping("/{id}/shipments") public ApiResponse<AfterSalesShipmentView> shipment(@PathVariable long id,@Valid @RequestBody AfterSalesShipmentRequest request){return ApiResponse.success("补发记录已保存",service.addShipment(id,request));}
    @PostMapping("/{id}/shipments/{shipmentId}/dispatch") public ApiResponse<AfterSalesShipmentView> dispatch(@PathVariable long id,@PathVariable long shipmentId,@Valid @RequestBody AfterSalesDispatchRequest request){return ApiResponse.success("发货已登记",service.dispatchShipment(id,shipmentId,request));}
    @GetMapping("/{id}/history") public ApiResponse<List<AfterSalesHistoryView>> history(@PathVariable long id){return ApiResponse.success("查询成功",service.history(id));}
    @PostMapping("/{id}/resolve") public ApiResponse<AfterSalesCaseView> resolve(@PathVariable long id){return ApiResponse.success("案例已标记解决",service.resolve(id));}
    @PostMapping("/{id}/close") public ApiResponse<AfterSalesCaseView> close(@PathVariable long id){return ApiResponse.success("案例已归档",service.close(id));}
}
