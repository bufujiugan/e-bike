package com.bike.server.pi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PiCreateRequest(
        @NotNull(message = "客户不能为空") @Positive Long customerId,
        @Positive Long agentId,
        @Positive Long sourceOrderId,
        @NotBlank(message = "贸易类型不能为空") @Size(max = 32) String tradeType,
        @NotBlank(message = "币种不能为空") @Size(max = 3) String currencyCode,
        LocalDate plannedShipDate,
        @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal discountAmount,
        @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal taxAmount,
        @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal freightAmount,
        @NotNull(message = "请明确填写保留库存所需收款金额")
        @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal depositRequiredAmount,
        @NotNull(message = "请明确填写放行发货所需收款金额")
        @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal shipmentRequiredAmount,
        @Size(max = 1000, message = "备注长度不能超过1000") String remark,
        @Valid PiDocumentSnapshot document,
        @NotEmpty(message = "至少需要一条 PI 明细") @Size(max = 100)
        @Valid List<@NotNull PiItemRequest> items,
        @DecimalMin("0.00") @Digits(integer=16,fraction=2) BigDecimal otherFeeAmount) {
    public PiCreateRequest(Long customerId,Long agentId,Long sourceOrderId,String tradeType,String currencyCode,LocalDate plannedShipDate,
            BigDecimal discountAmount,BigDecimal taxAmount,BigDecimal freightAmount,BigDecimal depositRequiredAmount,
            BigDecimal shipmentRequiredAmount,String remark,PiDocumentSnapshot document,List<PiItemRequest> items) {
        this(customerId,agentId,sourceOrderId,tradeType,currencyCode,plannedShipDate,discountAmount,taxAmount,freightAmount,
                depositRequiredAmount,shipmentRequiredAmount,remark,document,items,null);
    }
}

