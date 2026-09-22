package com.bike.server.pi;

import java.math.BigDecimal;

public record PiCalculatedItem(
        Long skuId, Long productModelId, String skuCode, String productName,
        String specification, String modelName, int quantity, BigDecimal unitPrice,
        BigDecimal discountAmount, BigDecimal taxAmount, BigDecimal lineTotal,
        String unit,String imageAttachmentId,String imageSha256) {
    public PiCalculatedItem(Long skuId,Long productModelId,String skuCode,String productName,String specification,String modelName,
            int quantity,BigDecimal unitPrice,BigDecimal discountAmount,BigDecimal taxAmount,BigDecimal lineTotal) {
        this(skuId,productModelId,skuCode,productName,specification,modelName,quantity,unitPrice,discountAmount,taxAmount,lineTotal,"台",null,null);
    }
}
