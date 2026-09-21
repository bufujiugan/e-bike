package com.bike.server.aftersales;

import com.bike.server.common.ApiResponse;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/after-sales")
public class AfterSalesCompanyController {
    private final AfterSalesCompanyService service;
    public AfterSalesCompanyController(AfterSalesCompanyService service){this.service=service;}
    @GetMapping("/companies") public ApiResponse<List<AfterSalesCompanyView>> list(){return ApiResponse.success(service.list());}
    @GetMapping("/companies/staff-options") public ApiResponse<Map<String,List<AfterSalesReferenceOption>>> staff(){return ApiResponse.success(service.staffOptions());}
    @GetMapping("/companies/{id}") public ApiResponse<AfterSalesCompanyView> get(@PathVariable long id){return ApiResponse.success(service.get(id));}
    @PutMapping("/companies/{id}") public ApiResponse<AfterSalesCompanyView> save(@PathVariable long id,@RequestBody AfterSalesContact contact){return ApiResponse.success(service.save(id,contact));}
    @GetMapping("/companies/{id}/salespeople") public ApiResponse<List<AfterSalesReferenceOption>> sales(@PathVariable long id){return ApiResponse.success(service.salespeople(id));}
    @PutMapping("/companies/{id}/salespeople") public ApiResponse<List<AfterSalesReferenceOption>> sales(@PathVariable long id,@RequestBody Salespeople request){return ApiResponse.success(service.setSalespeople(id,request.userIds()));}
    @GetMapping("/companies/{id}/members") public ApiResponse<List<AfterSalesMemberView>> members(@PathVariable long id){return ApiResponse.success(service.members(id));}
    @GetMapping("/companies/{id}/history") public ApiResponse<List<AfterSalesHistoryView>> history(@PathVariable long id){return ApiResponse.success(service.history(id));}
    @PutMapping("/cases/{id}/assignee") public ApiResponse<AfterSalesCaseView> assign(@PathVariable long id,@RequestBody Assignment request){return ApiResponse.success(service.assign(id,request.assigneeId()));}
    public record Salespeople(List<Long> userIds){}
    public record Assignment(Long assigneeId){}
}
