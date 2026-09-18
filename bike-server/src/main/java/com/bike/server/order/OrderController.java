package com.bike.server.order;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;
    public OrderController(OrderService orderService){this.orderService=orderService;}
    @GetMapping public ApiResponse<PageResponse<SalesOrderView>> list(@RequestParam(required=false) String keyword,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success("查询成功",orderService.list(keyword,status,page,pageSize));}
    @GetMapping("/by-no/{orderNo}") public ApiResponse<SalesOrderView> getByNo(@PathVariable String orderNo){return ApiResponse.success("查询成功",orderService.getByNo(orderNo));}
    @GetMapping("/{id}") public ApiResponse<SalesOrderView> get(@PathVariable long id){return ApiResponse.success("查询成功",orderService.get(id));}
    @PostMapping public ApiResponse<SalesOrderView> create(@Valid @RequestBody SalesOrderCreateRequest request){return ApiResponse.success("创建成功",orderService.create(request));}
    @PutMapping("/{id}") public ApiResponse<SalesOrderView> update(@PathVariable long id,@Valid @RequestBody SalesOrderCreateRequest request){return ApiResponse.success("更新成功",orderService.update(id,request));}
    @PostMapping("/{id}/confirm") public ApiResponse<SalesOrderView> confirm(@PathVariable long id){return ApiResponse.success("订单已确认",orderService.confirm(id));}
    @PostMapping("/{id}/cancel") public ApiResponse<SalesOrderView> cancel(@PathVariable long id,@RequestParam(required=false,defaultValue="业务作废") String reason){return ApiResponse.success("订单已作废",orderService.cancel(id,reason));}
}
