package com.bike.server.pi;

import java.math.BigDecimal;

public record PiCalculatedItem(
        Long skuId, Long productModelId, String skuCode, String productName,
        String specification, String modelName, int quantity, BigDecimal unitPrice,
        BigDecimal discountAmount, BigDecimal taxAmount, BigDecimal lineTotal) {}
