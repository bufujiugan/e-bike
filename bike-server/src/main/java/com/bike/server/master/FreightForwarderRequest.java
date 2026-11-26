package com.bike.server.master;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FreightForwarderRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 128) String contactName,
        @NotBlank @Size(max = 64) String phone,
        @NotBlank @Size(max = 1000) String address,
        @Size(max = 500) String remark) {

    FreightForwarderRequest normalized() {
        return new FreightForwarderRequest(name.strip(), contactName.strip(), phone.strip(), address.strip(),
                remark == null || remark.isBlank() ? null : remark.strip());
    }
}
