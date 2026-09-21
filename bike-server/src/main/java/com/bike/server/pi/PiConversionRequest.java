package com.bike.server.pi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record PiConversionRequest(@NotBlank @Size(max=64) String requestId,
        @NotEmpty @Size(max=100) @Valid List<@NotNull Source> sources,
        @NotNull @Valid PiCreateRequest pi) {
    public record Source(@Positive long lockId, @Positive @Max(1000000) int quantity) {}
}
