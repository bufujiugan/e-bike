package com.bike.server.pi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record PiFulfillmentProfileRequest(
        @NotBlank @Pattern(regexp="[A-Z0-9][A-Z0-9_-]{0,63}") String profileCode,
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,31}") String version,
        @NotBlank @Size(max=120) String name,
        @NotBlank @Pattern(regexp="DOMESTIC|EXPORT") String tradeType,
        @NotBlank @Size(max=200) String collectionEntity,
        @NotBlank @Size(max=64) String templateCode,
        @NotBlank @Size(max=32) String templateVersion,
        @NotNull @Valid PiFulfillmentRules rules,
        @NotBlank @Size(max=500) String reason) {}
