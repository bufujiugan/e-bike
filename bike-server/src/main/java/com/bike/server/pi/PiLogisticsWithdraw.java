package com.bike.server.pi;
import jakarta.validation.constraints.*;
public record PiLogisticsWithdraw(@NotBlank @Size(max=64) String requestId,@Positive int expectedRevision,@NotBlank @Size(max=500) String reason) {}
