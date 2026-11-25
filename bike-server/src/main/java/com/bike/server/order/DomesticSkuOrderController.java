package com.bike.server.order;

import com.bike.server.common.ApiResponse;
import com.bike.server.lock.InventoryLockView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DomesticSkuOrderController {
    private final DomesticSkuOrderService orders;

    public DomesticSkuOrderController(DomesticSkuOrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/api/orders/domestic-skus")
    public ApiResponse<SalesOrderView> create(@Valid @RequestBody DomesticSkuOrderRequest request) {
        return ApiResponse.success("内销订单已创建", orders.create(request));
    }

    @GetMapping("/api/orders/domestic-skus/options")
    public ApiResponse<List<DomesticSkuOption>> skuOptions(@RequestParam(defaultValue = "") String keyword) {
        return ApiResponse.success("查询成功", orders.skuOptions(keyword));
    }

    @GetMapping("/api/orders/domestic-skus/creation-enabled")
    public ApiResponse<Boolean> creationEnabled() {
        return ApiResponse.success("查询成功", orders.isCreationEnabled());
    }

    @GetMapping("/api/inventory-locks/{id}/domestic-order-sources")
    public ApiResponse<List<InventoryLockView>> sources(@PathVariable long id,
            @RequestParam(defaultValue = "") String keyword) {
        return ApiResponse.success("查询成功", orders.sources(id, keyword));
    }
}
