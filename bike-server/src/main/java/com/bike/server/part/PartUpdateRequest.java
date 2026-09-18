package com.bike.server.part;

import jakarta.validation.constraints.Size;

public record PartUpdateRequest(
        Long supplierId,
        @Size(max = 64, message = "批次号长度不能超过 64") String batchNo,
        String qcStatus,
        String status,
        @Size(max = 500, message = "备注长度不能超过 500") String remark) {
}
