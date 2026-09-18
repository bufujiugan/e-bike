package com.bike.server.assembly;

import jakarta.validation.constraints.NotBlank;

public record AssemblySubmitRequest(@NotBlank(message = "车架 SN 不能为空") String frameSn,
                                    @NotBlank(message = "电机 SN 不能为空") String motorSn,
                                    @NotBlank(message = "电池 SN 不能为空") String batterySn,
                                    @NotBlank(message = "仪表 SN 不能为空") String displaySn) {
}
