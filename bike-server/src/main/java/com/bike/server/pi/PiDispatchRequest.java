package com.bike.server.pi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record PiDispatchRequest(@NotBlank @Size(max=64) String requestId,
        @NotEmpty @Size(max=200) @Valid List<@NotNull Vehicle> vehicles) {
    public record Vehicle(@Positive long orderId,@NotBlank @Size(max=200) String vehicleQrCode) {}
}
