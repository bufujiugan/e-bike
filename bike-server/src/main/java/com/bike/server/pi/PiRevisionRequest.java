package com.bike.server.pi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PiRevisionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Positive int expectedRevision,
        @NotBlank @Size(max = 500) String reason) {}
