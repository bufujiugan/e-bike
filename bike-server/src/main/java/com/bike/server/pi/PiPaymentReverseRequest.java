package com.bike.server.pi;
import jakarta.validation.constraints.*;
public record PiPaymentReverseRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestId,
        @NotNull @Positive Long revision,@NotBlank @Size(max=500) String reason) {}
