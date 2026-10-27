package com.bike.server.pi;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PiWorkflowDetails(
        String contractNo,
        Boolean contractReady,
        Boolean paymentTableReady,
        Boolean productionTableReady,
        String productionStatus,
        LocalDate expectedCompletionDate,
        String materialStatus,
        String forwarderName,
        BigDecimal quotedAmount,
        String quoteCurrency,
        String domesticCarrier,
        BigDecimal domesticPrice,
        String domesticTrackingNo,
        LocalDate receivedDate) {}
