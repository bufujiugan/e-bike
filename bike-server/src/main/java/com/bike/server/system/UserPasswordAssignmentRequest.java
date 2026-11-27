package com.bike.server.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserPasswordAssignmentRequest(
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 72, message = "新密码至少 8 个字符，最多 72 字节")
        String newPassword) {
}
