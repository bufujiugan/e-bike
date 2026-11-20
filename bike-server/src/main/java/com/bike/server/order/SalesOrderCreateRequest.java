package com.bike.server.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 新建或编辑销售订单。salesType 仅在新建时写入（DOMESTIC/EXPORT/EUROPE），编辑不修改原类型；
 * 省略时为待选类型，需在订单办理中补选后才能确认。
 */
public record SalesOrderCreateRequest(@NotNull(message = "客户不能为空") Long customerId,
                                      Long agentId,
                                      @NotNull(message = "车型不能为空") Long modelId,
                                      @Min(value = 1, message = "订单数量必须大于 0") int orderQty,
                                      LocalDate plannedShipDate,
                                      @Size(max = 500, message = "备注长度不能超过 500") String remark,
                                      @Pattern(regexp = "DOMESTIC|EXPORT|EUROPE", message = "销售类型无效") String salesType) {
    /** 兼容原有调用方：不指定销售类型。 */
    public SalesOrderCreateRequest(Long customerId, Long agentId, Long modelId, int orderQty, LocalDate plannedShipDate, String remark) {
        this(customerId, agentId, modelId, orderQty, plannedShipDate, remark, null);
    }
}
