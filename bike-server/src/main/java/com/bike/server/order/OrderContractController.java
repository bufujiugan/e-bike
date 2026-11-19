package com.bike.server.order;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders/{orderId}/purchase-contract")
public class OrderContractController {
    private final OrderContractService contracts;

    public OrderContractController(OrderContractService contracts) { this.contracts = contracts; }

    @GetMapping
    public ApiResponse<OrderContractService.Preparation> prepare(@PathVariable long orderId) {
        return ApiResponse.success(contracts.prepareContract(orderId));
    }

    @PostMapping(value = "/export.xlsx", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> export(@PathVariable long orderId, @Valid @RequestBody OrderContractRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=purchase-contract-" + orderId + ".xlsx")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(contracts.generateContract(orderId, request));
    }
}
