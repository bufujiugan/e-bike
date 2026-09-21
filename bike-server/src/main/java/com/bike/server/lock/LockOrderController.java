package com.bike.server.lock;
import com.bike.server.common.ApiResponse;
import com.bike.server.order.SalesOrderView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class LockOrderController {
    private final LockOrderService service;
    public LockOrderController(LockOrderService service){this.service=service;}
    @GetMapping("/api/inventory-locks/{id}/orders")
    public ApiResponse<com.bike.server.common.PageResponse<SalesOrderView>> list(@PathVariable long id,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success("查询成功",service.list(id,page,pageSize));}
    @PostMapping("/api/inventory-locks/{id}/orders")
    public ApiResponse<SalesOrderView> convert(@PathVariable long id,@Valid @RequestBody LockOrderRequest request){return ApiResponse.success("订单已创建并保留库存占用",service.convert(id,request));}
}
