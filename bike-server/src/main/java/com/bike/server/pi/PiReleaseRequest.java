package com.bike.server.pi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PiReleaseRequest(
        @NotBlank(message = "请填写放行说明")
        @Size(max = 500, message = "放行说明长度不能超过500") String reason) {}
