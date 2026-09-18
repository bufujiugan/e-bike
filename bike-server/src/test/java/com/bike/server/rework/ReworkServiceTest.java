package com.bike.server.rework;

import com.bike.server.assembly.AssemblyPartRecord;
import com.bike.server.common.BusinessException;
import com.bike.server.vehicle.VehicleView;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReworkServiceTest {

    @org.junit.jupiter.api.BeforeEach
    void setAuthenticatedAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-admin", "N/A", java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listRejectsPdaOperatorFromAnotherWorkstation() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "assembly-pda", "N/A", java.util.List.of(
                new SimpleGrantedAuthority("ROLE_PDA_OPERATOR"),
                new SimpleGrantedAuthority("PERM_REWORK_OPERATE"),
                new SimpleGrantedAuthority("WORKSTATION_ASSEMBLY"))));
        ReworkMapper mapper = Mockito.mock(ReworkMapper.class);

        assertThatThrownBy(() -> new ReworkServiceImpl(mapper).list(1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("WORKSTATION_FORBIDDEN");
        Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void createUnbindsOldPartBindsNewPartAndWritesThreeAuditRecords() {
        ReworkMapper mapper = Mockito.mock(ReworkMapper.class);
        VehicleView vehicle = vehicle(9L, "FRAME-9", 1);
        AssemblyPartRecord oldPart = part(1L, "MOTOR-OLD", "MOTOR", "BOUND", "IN_USE");
        AssemblyPartRecord newPart = part(2L, "MOTOR-NEW", "MOTOR", "UNBOUND", "AVAILABLE");
        Mockito.when(mapper.selectVehicleForUpdate("FRAME-9")).thenReturn(vehicle);
        Mockito.when(mapper.selectCurrentPartForUpdate(9L, "MOTOR")).thenReturn(oldPart);
        Mockito.when(mapper.selectNewPartForUpdate("MOTOR-NEW")).thenReturn(newPart);
        Mockito.when(mapper.updateVehicleStatus(Mockito.eq(9L), Mockito.anyInt())).thenReturn(1, 1);
        Mockito.when(mapper.insertRework(Mockito.anyString(), Mockito.eq(9L), Mockito.eq("FRAME-9"), Mockito.eq("更换原因"), Mockito.isNull())).thenReturn(1);
        Mockito.when(mapper.insertDetail(Mockito.anyString(), Mockito.eq("MOTOR"), Mockito.eq(1L), Mockito.eq("MOTOR-OLD"), Mockito.eq(2L), Mockito.eq("MOTOR-NEW"))).thenReturn(1);
        Mockito.when(mapper.unbindOld(9L, "MOTOR", "更换原因", null)).thenReturn(1);
        Mockito.when(mapper.releaseOldPart(1L)).thenReturn(1);
        Mockito.when(mapper.insertPartUnbindOperationLog("MOTOR-OLD", "MOTOR", "FRAME-9", null, "test-admin", "更换原因", null)).thenReturn(1);
        Mockito.when(mapper.bindNew(9L, "FRAME-9", 2L, "MOTOR-NEW", "MOTOR", "更换原因", null)).thenReturn(1);
        Mockito.when(mapper.occupyNewPart(2L, 9L, "FRAME-9")).thenReturn(1);
        Mockito.when(mapper.insertPartBindOperationLog("MOTOR-NEW", "MOTOR", "FRAME-9", null, "test-admin", "更换原因", null)).thenReturn(1);
        Mockito.when(mapper.selectReworkIdByNo(Mockito.anyString())).thenReturn(12);
        ReworkView result = new ReworkView();
        result.setFrameSn("FRAME-9");
        result.setNewPartSn("MOTOR-NEW");
        Mockito.when(mapper.selectById(12L)).thenReturn(result);

        ReworkView actual = new ReworkServiceImpl(mapper)
                .create(new ReworkRequest(" FRAME-9 ", "motor", " MOTOR-NEW ", "更换原因"));

        assertThat(actual.getNewPartSn()).isEqualTo("MOTOR-NEW");
        Mockito.verify(mapper).insertPartUnbindOperationLog("MOTOR-OLD", "MOTOR", "FRAME-9", null, "test-admin", "更换原因", null);
        Mockito.verify(mapper).insertPartBindOperationLog("MOTOR-NEW", "MOTOR", "FRAME-9", null, "test-admin", "更换原因", null);
        Mockito.verify(mapper).insertOperationLog(Mockito.anyString(), Mockito.eq("FRAME-9"), Mockito.eq("MOTOR"), Mockito.eq("MOTOR-OLD"), Mockito.eq("MOTOR-NEW"), Mockito.isNull(), Mockito.eq("test-admin"), Mockito.eq("更换原因"), Mockito.isNull());
        Mockito.verify(mapper).updateVehicleStatus(9L, 1);
    }

    @Test
    void createRejectsOccupiedReplacementPartBeforeChangingVehicle() {
        ReworkMapper mapper = Mockito.mock(ReworkMapper.class);
        Mockito.when(mapper.selectVehicleForUpdate("FRAME-9")).thenReturn(vehicle(9L, "FRAME-9", 2));
        Mockito.when(mapper.selectCurrentPartForUpdate(9L, "BATTERY")).thenReturn(part(1L, "BAT-OLD", "BATTERY", "BOUND", "IN_USE"));
        Mockito.when(mapper.selectNewPartForUpdate("BAT-NEW")).thenReturn(part(2L, "BAT-NEW", "BATTERY", "BOUND", "IN_USE"));

        assertThatThrownBy(() -> new ReworkServiceImpl(mapper)
                .create(new ReworkRequest("FRAME-9", "BATTERY", "BAT-NEW", "故障")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被其他整车绑定");
        Mockito.verify(mapper, Mockito.never()).updateVehicleStatus(Mockito.anyLong(), Mockito.anyInt());
    }

    private static VehicleView vehicle(long id, String frame, int status) {
        VehicleView v = new VehicleView();
        v.setId(id);
        v.setFrameSn(frame);
        v.setStatus(status);
        return v;
    }

    private static AssemblyPartRecord part(long id, String sn, String type, String bindStatus, String status) {
        AssemblyPartRecord p = new AssemblyPartRecord();
        p.setId(id);
        p.setPartSn(sn);
        p.setPartType(type);
        p.setBindStatus(bindStatus);
        p.setStatus(status);
        return p;
    }
}
