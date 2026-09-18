package com.bike.server.system;
import jakarta.validation.constraints.NotBlank;
public record WorkstationRequest(@NotBlank(message="工位编码不能为空") String workstationCode,@NotBlank(message="工位名称不能为空") String workstationName,@NotBlank(message="工位类型不能为空") String workstationType) {}
