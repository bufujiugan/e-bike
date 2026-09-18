package com.bike.server.assembly;

import jakarta.validation.constraints.NotBlank;

public record AssemblyScanRequest(@NotBlank(message = "车架 SN 不能为空") String frameSn,
                                  @NotBlank(message = "零部件 SN 不能为空") String partSn,
                                  @NotBlank(message = "期望零部件类型不能为空") String expectedPartType) {
}
