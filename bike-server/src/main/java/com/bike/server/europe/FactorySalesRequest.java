package com.bike.server.europe;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

public record FactorySalesRequest(
        @NotBlank @Size(max=64) String requestId,
        @Positive int revision,
        @NotNull @Positive Long customerId,
        @Positive Long warehouseId,
        @NotBlank @Pattern(regexp="FACTORY_DOMESTIC|FACTORY_EXPORT") String businessType,
        @NotBlank @Pattern(regexp="[A-Z]{3}") String currencyCode,
        @Size(max=200) String factoryName,
        @Size(max=200) String contractNo,
        @Size(max=4000) String goodsDescription,
        @JsonFormat(shape=JsonFormat.Shape.STRING) @DecimalMin("0.00") @Digits(integer=16,fraction=2) BigDecimal netAmount,
        @JsonFormat(shape=JsonFormat.Shape.STRING) @DecimalMin("0.00") @Digits(integer=16,fraction=2) BigDecimal vatAmount,
        @Size(max=1000) String paymentTerms,
        LocalDate paymentDueDate,
        LocalDate pickupDate,
        @Size(max=1000) String pickupAddress,
        @Size(max=300) String pickupContact,
        @Size(max=2000) String preparationNote,
        @Size(max=2000) String deliveryNote,
        @Size(max=2000) String collectionNote,
        @Size(max=4000) String remark,
        @Size(max=100) List<@NotNull @Positive Long> orderIds) {}
