package com.bike.server.pi;

import jakarta.validation.constraints.*;

public record PiTemplateRequest(
        @NotBlank @Pattern(regexp="[A-Z0-9][A-Z0-9_-]{0,63}") String templateCode,
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,31}") String templateVersion,
        @NotBlank @Pattern(regexp="DOMESTIC|EXPORT") String tradeType,
        @NotBlank @Pattern(regexp="DOMESTIC_V[12]|EXPORT_V[12]") String layoutCode,
        @NotBlank @Size(max=200) String sellerName,
        @NotBlank @Size(max=1000) String sellerAddress,
        @NotBlank @Size(max=300) String sellerContact,
        @NotBlank @Size(max=200) String bankName,
        @NotBlank @Size(max=200) String bankAccount,
        @NotBlank @Size(max=200) String beneficiary,
        @NotBlank @Size(max=500) String reason) {}
