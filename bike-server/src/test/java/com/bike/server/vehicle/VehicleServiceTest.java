package com.bike.server.vehicle;

import com.bike.server.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VehicleServiceTest {

    @Test
    void vehicleCreateRejectsNonAdminEvenWhenServiceIsCalledDirectly() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "operator", null, List.of(new SimpleGrantedAuthority("ROLE_WEB_OPERATOR"))));
        VehicleMapper mapper = mock(VehicleMapper.class);

        assertThatThrownBy(() -> new VehicleServiceImpl(mapper).create(new VehicleCreateRequest("FRAME-1", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("PERMISSION_DENIED");
        verifyNoInteractions(mapper);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void businessOperatorCannotRegenerateVehicleQrCode() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "operator", null, List.of(
                        new SimpleGrantedAuthority("ROLE_WEB_OPERATOR"),
                        new SimpleGrantedAuthority("PERM_VEHICLE_READ"))));
        VehicleMapper mapper = mock(VehicleMapper.class);

        assertThatThrownBy(() -> new VehicleServiceImpl(mapper).regenerateQr(7L))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("PERMISSION_DENIED");
        verifyNoInteractions(mapper);
    }

    @Test
    void superAdminRegeneratesQrAndWritesVersionAudit() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        VehicleMapper mapper = mock(VehicleMapper.class);
        VehicleView before = new VehicleView();
        before.setId(7L);
        before.setQrVersion(2);
        VehicleView after = new VehicleView();
        after.setId(7L);
        after.setQrVersion(3);
        when(mapper.selectById(7L)).thenReturn(before, after);
        when(mapper.updateQr(eq(7L), anyString(), anyString(), eq(3))).thenReturn(1);
        when(mapper.insertOperationLog(eq("7"), eq("重新生成整车二维码"), isNull(), eq("admin"), eq("qrVersion=2"), eq("qrVersion=3"), isNull())).thenReturn(1);

        new VehicleServiceImpl(mapper).regenerateQr(7L);

        verify(mapper).updateQr(eq(7L), startsWith("BIKE:7:"), anyString(), eq(3));
        verify(mapper).insertOperationLog(eq("7"), eq("重新生成整车二维码"), isNull(), eq("admin"), eq("qrVersion=2"), eq("qrVersion=3"), isNull());
    }
}
