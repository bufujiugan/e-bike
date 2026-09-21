package com.bike.server.pi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PiCancelRequest(
        @NotBlank(message = "请填写取消原因") @Size(max = 500) String reason) {}
