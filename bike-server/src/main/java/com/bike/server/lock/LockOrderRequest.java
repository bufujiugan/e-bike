package com.bike.server.lock;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record LockOrderRequest(@NotNull @Positive Long customerId,
                              @Min(1) int quantity,
                              @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String requestId,
                              LocalDate plannedShipDate) {}
