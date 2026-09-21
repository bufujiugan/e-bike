package com.bike.server.pi;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record PiItemRequest(
        @NotNull(message = "SKU不能为空") @Positive Long skuId,
        @Positive(message = "数量必须大于0") int quantity,
        @NotNull(message = "单价不能为空") @DecimalMin("0.00")
        @Digits(integer = 16, fraction = 2) BigDecimal unitPrice,
        @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal discountAmount,
        @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal taxAmount) {}
