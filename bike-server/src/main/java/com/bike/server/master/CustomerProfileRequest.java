package com.bike.server.master;

import jakarta.validation.constraints.*;

public record CustomerProfileRequest(
        @NotNull @PositiveOrZero Long revision,
        @Size(max=1000) String address,
        @Size(max=100) String country,
        @Email @Size(max=254) String email,
        @Size(max=3) String preferredCurrency,
        @Size(max=150) String businessOwner,
        @NotBlank @Size(max=500) String reason) {}
