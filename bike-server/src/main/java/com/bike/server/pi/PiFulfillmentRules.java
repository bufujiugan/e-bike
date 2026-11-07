package com.bike.server.pi;

import jakarta.validation.constraints.*;
import java.util.List;

/** 履约规则与国家无关；发布后的版本仅供引用，不允许覆盖。 */
public record PiFulfillmentRules(
        @NotBlank @Pattern(regexp="PREPAY|CREDIT") String paymentMode,
        @NotBlank @Pattern(regexp="SELLER_TRANSPORT|CUSTOMER_PICKUP") String deliveryMode,
        @NotNull Boolean viaForwarderWarehouse,
        @NotNull Boolean salesContractRequired,
        @NotNull Boolean purchaseContractRequired,
        @NotNull Boolean dispatchSheetRequired,
        @NotNull Boolean preparationRequired,
        @NotNull Boolean settlementRequired,
        @NotNull @Size(max=3) List<@Pattern(regexp="CI|RD|CMR") String> requiredDocuments,
        @NotNull @Size(max=100) List<@Positive Long> warehouseIds) {
    public boolean credit() { return "CREDIT".equals(paymentMode); }
    public boolean customerPickup() { return "CUSTOMER_PICKUP".equals(deliveryMode); }
}
