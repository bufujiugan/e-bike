package com.bike.server.rework;

import jakarta.validation.constraints.NotBlank;

public record ReworkRequest(@NotBlank(message="车架 SN 不能为空") String frameSn,
                            @NotBlank(message="更换类型不能为空") String partType,
                            @NotBlank(message="新零部件 SN 不能为空") String newPartSn,
                            @NotBlank(message="返工原因不能为空") String reason) {}
