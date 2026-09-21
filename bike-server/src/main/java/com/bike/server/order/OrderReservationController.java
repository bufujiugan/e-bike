package com.bike.server.order;
import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders/{id}")
public class OrderReservationController {
    private final OrderReservationService service;
    public OrderReservationController(OrderReservationService service){this.service=service;}
    public record Renew(@Min(1) @Max(720) int hours,@NotBlank @Size(max=500) String reason){}
    public record Close(@NotBlank @Size(max=500) String reason){}
    @PostMapping("/reservation/renew") public ApiResponse<SalesOrderView> renew(@PathVariable long id,@Valid @RequestBody Renew request){return ApiResponse.success("库存占用已重新确认",service.renew(id,request.hours(),request.reason()));}
    @PostMapping("/close-remainder") public ApiResponse<SalesOrderView> close(@PathVariable long id,@Valid @RequestBody Close request){return ApiResponse.success("剩余数量已关闭",service.closeRemainder(id,request.reason()));}
}
