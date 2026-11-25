package com.bike.server.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record OrderContractRequest(
        @NotBlank @Size(max = 60) String contractNo,
        @NotNull LocalDate signedDate,
        @NotNull LocalDateTime orderUpdatedAt,
        @Positive long modelId,
        @Positive int orderQty,
        @NotNull @Valid Party buyer,
        @NotNull @Valid Party seller,
        @Size(max = 40) String customsModel,
        @NotBlank @Size(max = 60) String productName,
        @Size(max = 40) String appearance,
        @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal unitPrice,
        @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal usdUnitPrice,
        @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal costUnitPrice,
        @NotBlank @Size(max = 160) String paymentTerms,
        @Size(max = 120) String bankName,
        @Size(max = 80) String bankAccount,
        @NotNull @Size(min = 12, max = 12) List<@NotBlank @Size(max = 300) String> clauses,
        @Size(max = 100) List<@NotNull @Valid SkuPrice> skuPrices) {
    public OrderContractRequest(String contractNo, LocalDate signedDate, LocalDateTime orderUpdatedAt,
            long modelId, int orderQty, Party buyer, Party seller, String customsModel, String productName,
            String appearance, BigDecimal unitPrice, BigDecimal usdUnitPrice, BigDecimal costUnitPrice,
            String paymentTerms, String bankName, String bankAccount, List<String> clauses) {
        this(contractNo, signedDate, orderUpdatedAt, modelId, orderQty, buyer, seller, customsModel, productName,
                appearance, unitPrice, usdUnitPrice, costUnitPrice, paymentTerms, bankName, bankAccount, clauses, null);
    }

    public record SkuPrice(@Positive long skuId, String skuCode, String modelName, @Positive int quantity,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal unitPrice) {}

    public record Party(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 160) String address,
            @Size(max = 40) String contact,
            @Size(max = 60) String phone,
            @Size(max = 60) String fax,
            @Size(max = 100) String email) {}
}
