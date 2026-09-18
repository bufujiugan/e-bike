package com.bike.server.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SystemConfigRequest(@NotBlank String configValue, @Size(max = 500) String remark) { }
