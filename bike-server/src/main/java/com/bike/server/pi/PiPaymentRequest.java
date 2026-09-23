package com.bike.server.pi;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PiPaymentRequest(
        @NotBlank(message = "收款请求标识不能为空") @Size(max = 64) String requestId,
        @NotNull(message = "收款金额不能为空") @DecimalMin("0.01")
        @Digits(integer = 16, fraction = 2) BigDecimal amount,
        @NotBlank(message = "币种不能为空") @Size(max = 3) String currencyCode,
        @Size(max = 500) String voucherObjectKey,
        LocalDateTime receivedAt,
        @Size(max = 500) String remark,
        @Pattern(regexp="BANK_TRANSFER|CASH|OTHER") String paymentMethod,
        @Size(max=200) String paymentMethodNote) {
    public PiPaymentRequest(String requestId,BigDecimal amount,String currencyCode,String voucherObjectKey,LocalDateTime receivedAt,String remark) {
        this(requestId,amount,currencyCode,voucherObjectKey,receivedAt,remark,null,null);
    }
}
