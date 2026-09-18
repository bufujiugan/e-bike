package com.bike.server.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record SalesOrderCreateRequest(@NotNull(message = "客户不能为空") Long customerId,
                                      Long agentId,
                                      @NotNull(message = "车型不能为空") Long modelId,
                                      @Min(value = 1, message = "订单数量必须大于 0") int orderQty,
                                      LocalDate plannedShipDate,
                                      @Size(max = 500, message = "备注长度不能超过 500") String remark) {
}
