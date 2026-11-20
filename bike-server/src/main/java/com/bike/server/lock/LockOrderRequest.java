package com.bike.server.lock;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

/**
 * 锁定转订单。salesType 为转单去向：只有 DOMESTIC（内销购销合同）在此生成订单；
 * 外销、欧洲须在对应菜单中从 SKU 锁定生成 PI。
 */
public record LockOrderRequest(@NotNull @Positive Long customerId,
                              @Min(1) int quantity,
                              @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String requestId,
                              LocalDate plannedShipDate,
                              @Pattern(regexp="DOMESTIC|EXPORT|EUROPE", message="销售去向无效") String salesType) {
    public LockOrderRequest(Long customerId, int quantity, String requestId, LocalDate plannedShipDate) {
        this(customerId, quantity, requestId, plannedShipDate, null);
    }
}
