package com.bike.server.pi;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record PiItemRequest(
        @NotNull(message = "SKU不能为空") @Positive Long skuId,
        @Positive(message = "数量必须大于0") int quantity,
        @NotNull(message = "单价不能为空") @DecimalMin("0.00")
        @Digits(integer = 16, fraction = 2) BigDecimal unitPrice,
        @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal discountAmount,
        @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal taxAmount,
        @NotBlank @Size(max=32) String unit,
        @Size(max=36) String imageAttachmentId) {
    public PiItemRequest { if(unit==null)unit="台"; }
    public PiItemRequest(Long skuId,int quantity,BigDecimal unitPrice,BigDecimal discountAmount,BigDecimal taxAmount) {
        this(skuId,quantity,unitPrice,discountAmount,taxAmount,"台",null);
    }
}
