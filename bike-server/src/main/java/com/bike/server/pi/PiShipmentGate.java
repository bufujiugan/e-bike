package com.bike.server.pi;

import java.math.BigDecimal;

public record PiShipmentGate(Long id, Long customerId, String status, String releaseStatus,
                             BigDecimal shipmentRequiredAmount, BigDecimal paidAmount) {}
