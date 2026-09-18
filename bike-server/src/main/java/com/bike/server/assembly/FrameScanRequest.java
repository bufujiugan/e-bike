package com.bike.server.assembly;

import jakarta.validation.constraints.NotBlank;

public record FrameScanRequest(@NotBlank(message = "车架 SN 不能为空") String frameSn) {
}
