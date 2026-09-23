package com.bike.server.pi;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PiPaymentView(
        Long id, Long piId, String requestId, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, String currencyCode,
        String voucherObjectKey, String status, LocalDateTime receivedAt,
        Long confirmedBy, String remark, String paymentMethod, String paymentMethodNote, LocalDateTime recordedAt,
        long revision, @com.fasterxml.jackson.annotation.JsonIgnore String reversalRequestId, String reversalReason,
        Long reversedBy, LocalDateTime reversedAt) {
    @org.apache.ibatis.annotations.AutomapConstructor public PiPaymentView {}
    public PiPaymentView(Long id,Long piId,String requestId,BigDecimal amount,String currencyCode,String voucherObjectKey,String status,
            LocalDateTime receivedAt,Long confirmedBy,String remark) {
        this(id,piId,requestId,amount,currencyCode,voucherObjectKey,status,receivedAt,confirmedBy,remark,null,null,null,1,null,null,null,null);
    }
}

