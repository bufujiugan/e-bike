package com.bike.server.europe;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SalesSettlementRequest(@NotBlank @Size(max=64) String requestId,
        @NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal amount,
        @NotBlank @Pattern(regexp="[A-Z]{3}") String currencyCode,
        @NotNull LocalDateTime occurredAt,
        @Size(max=200) String counterparty,
        @Size(max=300) String referenceNo,
        @Size(max=2000) String remark) {}
