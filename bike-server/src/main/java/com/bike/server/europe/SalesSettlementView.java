package com.bike.server.europe;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

public record SalesSettlementView(long id, String requestId, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount, String currencyCode,
        LocalDateTime occurredAt, String counterparty, String referenceNo, String remark,
        String status, LocalDateTime createdAt, String reversalReason) {}
