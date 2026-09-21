package com.bike.server.part;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PartCreateRequest(
        @NotBlank(message = "零部件 SN 不能为空") @Size(max = 128, message = "零部件 SN 长度不能超过 128") String partSn,
        @NotBlank(message = "零部件类型不能为空") @Size(max = 32, message = "零部件类型长度不能超过 32") String partType,
        Long supplierId,
        @Size(max = 64, message = "批次号长度不能超过 64") String batchNo,
        @jakarta.validation.constraints.Pattern(regexp = "PASSED|FAILED|PENDING", message = "质检状态须为PASSED、FAILED或PENDING") String qcStatus,
        @Size(max = 500, message = "备注长度不能超过500") String remark) {
}
