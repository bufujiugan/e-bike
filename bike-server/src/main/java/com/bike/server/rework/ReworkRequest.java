package com.bike.server.rework;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public record ReworkRequest(@Size(max=128) String frameSn,
                            @NotBlank(message="更换类型不能为空") String partType,
                            @NotBlank(message="新零部件 SN 不能为空") String newPartSn,
                            @NotBlank(message="返工原因不能为空") String reason,
                            @Size(max=200) String vehicleQrCode) {
    public ReworkRequest(String frameSn, String partType, String newPartSn, String reason) {
        this(frameSn, partType, newPartSn, reason, null);
    }

    @AssertTrue(message="请扫描车辆聚合码")
    public boolean isVehicleIdentifierPresent() {
        return vehicleQrCode != null && !vehicleQrCode.isBlank() || frameSn != null && !frameSn.isBlank();
    }
}
