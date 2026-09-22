package com.bike.server.aftersales;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AfterSalesReturnRequest(@Min(1) long revision, @NotBlank @Size(max=32) String action,
    @Size(max=128) String warehouseName, @Size(max=100) String recipient, @Size(max=64) String phone,
    @Size(max=500) String addressLine, @Size(max=32) String postalCode,
    @Size(max=128) String carrier, @Size(max=128) String trackingNo,
    @Size(max=2000) String inspectionResult, @Size(max=1000) String reason) {}
