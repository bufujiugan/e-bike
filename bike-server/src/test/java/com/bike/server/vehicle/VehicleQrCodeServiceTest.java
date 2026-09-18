package com.bike.server.vehicle;

import com.bike.server.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VehicleQrCodeServiceTest {
    @Test
    void rendersPngFromPersistedVehicleQrCode() {
        VehicleService vehicleService = mock(VehicleService.class);
        VehicleView vehicle = new VehicleView();
        vehicle.setId(7L);
        vehicle.setVehicleQrCode("BIKE:7:test-token");
        when(vehicleService.getById(7L)).thenReturn(vehicle);

        byte[] png = new VehicleQrCodeService(vehicleService).renderPng(7L, 1);

        assertThat(png).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        assertThat(png.length).isGreaterThan(100);
    }

    @Test
    void rejectsVehicleWithoutGeneratedQrCode() {
        VehicleService vehicleService = mock(VehicleService.class);
        when(vehicleService.getById(8L)).thenReturn(new VehicleView());

        assertThatThrownBy(() -> new VehicleQrCodeService(vehicleService).renderPng(8L, 320))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("QR_NOT_AVAILABLE");
    }
}
