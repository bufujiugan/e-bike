package com.bike.server.master;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MasterUpdateRequest(@NotBlank(message = "编码不能为空") @Size(max = 64, message = "编码长度不能超过 64") String code,
                                  @NotBlank(message = "名称不能为空") @Size(max = 150, message = "名称长度不能超过 150") String name,
                                  @Size(max = 100, message = "联系人长度不能超过 100") String contactName,
                                  @Size(max = 64, message = "联系电话长度不能超过 64") String contactPhone) {
}
