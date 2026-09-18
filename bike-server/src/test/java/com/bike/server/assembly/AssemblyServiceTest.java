package com.bike.server.assembly;

import com.bike.server.vehicle.VehicleMapper;
import com.bike.server.vehicle.VehicleView;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class AssemblyServiceTest {
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
    void getByFrameRejectsPdaOperatorFromAnotherWorkstation() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "stock-in-pda", "N/A", java.util.List.of(
                new SimpleGrantedAuthority("ROLE_PDA_OPERATOR"),
                new SimpleGrantedAuthority("PERM_ASSEMBLY_OPERATE"),
                new SimpleGrantedAuthority("WORKSTATION_STOCK_IN"))));
        VehicleMapper vehicleMapper = Mockito.mock(VehicleMapper.class);
        AssemblyMapper mapper = Mockito.mock(AssemblyMapper.class);

        assertThatThrownBy(() -> new AssemblyServiceImpl(vehicleMapper, mapper).getByFrame("FRAME-1"))
                .isInstanceOf(com.bike.server.common.BusinessException.class)
                .hasMessageContaining("工位");
        Mockito.verifyNoInteractions(vehicleMapper, mapper);
    }

    @Test
    void validatePartRejectsWrongQrType() {
        AssemblyMapper mapper = Mockito.mock(AssemblyMapper.class);
        VehicleMapper vehicleMapper = Mockito.mock(VehicleMapper.class);
        AssemblyPartRecord part = new AssemblyPartRecord();
        part.setPartSn("BAT-1");
        part.setPartType("BATTERY");
        part.setStatus("AVAILABLE");
        part.setBindStatus("UNBOUND");
        Mockito.when(mapper.selectPartForUpdate("BAT-1")).thenReturn(part);
        AssemblyService service = new AssemblyServiceImpl(vehicleMapper, mapper);

        AssemblyValidationResult result = service.validatePart(new AssemblyScanRequest("FRAME-1", "BAT-1", "MOTOR"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("当前应扫描MOTOR");
    }

    @Test
    void validateFrameRejectsAlreadyAssembledVehicle() {
        AssemblyMapper mapper = Mockito.mock(AssemblyMapper.class);
        VehicleMapper vehicleMapper = Mockito.mock(VehicleMapper.class);
        VehicleView vehicle = new VehicleView();
        vehicle.setFrameSn("FRAME-1");
        vehicle.setStatus(1);
        Mockito.when(vehicleMapper.selectByFrame("FRAME-1")).thenReturn(vehicle);
        AssemblyService service = new AssemblyServiceImpl(vehicleMapper, mapper);

        AssemblyValidationResult result = service.validateFrame(" FRAME-1 ");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("不允许组装");
    }

    @Test
    void submitBindsFourCodesGeneratesQrAndWritesAudit() {
        AssemblyMapper mapper = Mockito.mock(AssemblyMapper.class);
        VehicleMapper vehicleMapper = Mockito.mock(VehicleMapper.class);
        VehicleView vehicle = vehicle("FRAME-1", 0, 10L);
        Mockito.when(vehicleMapper.selectByFrame("FRAME-1")).thenReturn(vehicle);
        Mockito.when(mapper.selectPartForUpdate("MOTOR-1")).thenReturn(part(1L, "MOTOR-1", "MOTOR"));
        Mockito.when(mapper.selectPartForUpdate("BATTERY-1")).thenReturn(part(2L, "BATTERY-1", "BATTERY"));
        Mockito.when(mapper.selectPartForUpdate("DISPLAY-1")).thenReturn(part(3L, "DISPLAY-1", "DISPLAY"));
        Mockito.when(mapper.markPartBound(Mockito.anyLong(), Mockito.eq(10L), Mockito.eq("FRAME-1"))).thenReturn(1);
        Mockito.when(mapper.insertPartBindingLog(Mockito.anyString(), Mockito.eq("FRAME-1"), Mockito.isNull(), Mockito.eq("test-admin"), Mockito.isNull())).thenReturn(1);
        Mockito.when(vehicleMapper.updateAssembled(Mockito.eq(10L), Mockito.isNull(), Mockito.anyString(), Mockito.anyString(), Mockito.eq(1))).thenReturn(1);
        VehicleView assembled = vehicle("FRAME-1", 1, 10L);
        assembled.setVehicleQrCode("BIKE:10:TOKEN");
        Mockito.when(vehicleMapper.selectByFrameForUpdate("FRAME-1")).thenReturn(vehicle);
        Mockito.when(vehicleMapper.selectByFrame("FRAME-1")).thenReturn(assembled);

        VehicleView result = new AssemblyServiceImpl(vehicleMapper, mapper)
                .submit(new AssemblySubmitRequest(" FRAME-1 ", " MOTOR-1 ", " BATTERY-1 ", " DISPLAY-1 "));

        assertThat(result.getStatus()).isEqualTo(1);
        Mockito.verify(mapper).insertBinding(10L, "FRAME-1", 1L, "MOTOR-1", "MOTOR", null, "test-admin");
        Mockito.verify(mapper).insertBinding(10L, "FRAME-1", 2L, "BATTERY-1", "BATTERY", null, "test-admin");
        Mockito.verify(mapper).insertBinding(10L, "FRAME-1", 3L, "DISPLAY-1", "DISPLAY", null, "test-admin");
        Mockito.verify(mapper).insertOperationLog("10", "四码绑定", "FRAME-1", null, "test-admin", "组装提交", null);
    }

    @Test
    void submitRejectsUnavailablePartBeforeMutatingVehicle() {
        AssemblyMapper mapper = Mockito.mock(AssemblyMapper.class);
        VehicleMapper vehicleMapper = Mockito.mock(VehicleMapper.class);
        Mockito.when(vehicleMapper.selectByFrameForUpdate("FRAME-1")).thenReturn(vehicle("FRAME-1", 0, 10L));
        AssemblyPartRecord unavailable = part(1L, "MOTOR-1", "MOTOR");
        unavailable.setStatus("IN_USE");
        Mockito.when(mapper.selectPartForUpdate("MOTOR-1")).thenReturn(unavailable);

        assertThatThrownBy(() -> new AssemblyServiceImpl(vehicleMapper, mapper)
                .submit(new AssemblySubmitRequest("FRAME-1", "MOTOR-1", "BATTERY-1", "DISPLAY-1")))
                .isInstanceOf(com.bike.server.common.BusinessException.class)
                .hasMessageContaining("不可用");
        Mockito.verify(vehicleMapper, Mockito.never()).updateAssembled(Mockito.anyLong(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.anyInt());
        Mockito.verify(mapper, Mockito.never()).insertBinding(Mockito.anyLong(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.anyString());
    }

    @Test
    void submitConvertsDatabaseUniqueConflictToPartAlreadyBound() {
        AssemblyMapper mapper = Mockito.mock(AssemblyMapper.class);
        VehicleMapper vehicleMapper = Mockito.mock(VehicleMapper.class);
        Mockito.when(vehicleMapper.selectByFrameForUpdate("FRAME-1")).thenReturn(vehicle("FRAME-1", 0, 10L));
        Mockito.when(mapper.selectPartForUpdate("MOTOR-1")).thenReturn(part(1L, "MOTOR-1", "MOTOR"));
        Mockito.when(mapper.insertBinding(Mockito.anyLong(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.anyString()))
                .thenThrow(new DataIntegrityViolationException("duplicate current binding"));

        assertThatThrownBy(() -> new AssemblyServiceImpl(vehicleMapper, mapper)
                .submit(new AssemblySubmitRequest("FRAME-1", "MOTOR-1", "BATTERY-1", "DISPLAY-1")))
                .isInstanceOf(com.bike.server.common.BusinessException.class)
                .extracting(com.bike.server.common.BusinessException.class::cast)
                .extracting(com.bike.server.common.BusinessException::getCode)
                .isEqualTo("PART_ALREADY_BOUND");
        Mockito.verify(vehicleMapper, Mockito.never()).updateAssembled(Mockito.anyLong(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.anyInt());
    }

    @Test
    void submitRejectsAlreadyBoundPartBeforeWritingBinding() {
        AssemblyMapper mapper = Mockito.mock(AssemblyMapper.class);
        VehicleMapper vehicleMapper = Mockito.mock(VehicleMapper.class);
        Mockito.when(vehicleMapper.selectByFrameForUpdate("FRAME-1")).thenReturn(vehicle("FRAME-1", 0, 10L));
        AssemblyPartRecord bound = part(1L, "MOTOR-1", "MOTOR");
        bound.setBindStatus("BOUND");
        Mockito.when(mapper.selectPartForUpdate("MOTOR-1")).thenReturn(bound);

        assertThatThrownBy(() -> new AssemblyServiceImpl(vehicleMapper, mapper)
                .submit(new AssemblySubmitRequest("FRAME-1", "MOTOR-1", "BATTERY-1", "DISPLAY-1")))
                .isInstanceOf(com.bike.server.common.BusinessException.class)
                .hasMessageContaining("已被其他整车绑定");
        Mockito.verify(mapper, Mockito.never()).insertBinding(Mockito.anyLong(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.anyString());
    }

    @Test
    void submitRejectsMissingAndScrappedParts() {
        AssemblyMapper missingMapper = Mockito.mock(AssemblyMapper.class);
        VehicleMapper vehicleMapper = Mockito.mock(VehicleMapper.class);
        Mockito.when(vehicleMapper.selectByFrameForUpdate("FRAME-1")).thenReturn(vehicle("FRAME-1", 0, 10L));
        assertThatThrownBy(() -> new AssemblyServiceImpl(vehicleMapper, missingMapper)
                .submit(new AssemblySubmitRequest("FRAME-1", "MOTOR-1", "BATTERY-1", "DISPLAY-1")))
                .isInstanceOf(com.bike.server.common.BusinessException.class)
                .hasMessageContaining("零部件不存在");

        AssemblyMapper scrappedMapper = Mockito.mock(AssemblyMapper.class);
        AssemblyPartRecord scrapped = part(1L, "MOTOR-1", "MOTOR");
        scrapped.setStatus("SCRAPPED");
        Mockito.when(scrappedMapper.selectPartForUpdate("MOTOR-1")).thenReturn(scrapped);
        assertThatThrownBy(() -> new AssemblyServiceImpl(vehicleMapper, scrappedMapper)
                .submit(new AssemblySubmitRequest("FRAME-1", "MOTOR-1", "BATTERY-1", "DISPLAY-1")))
                .isInstanceOf(com.bike.server.common.BusinessException.class)
                .hasMessageContaining("不可用");
    }

    private static VehicleView vehicle(String frameSn, int status, long id) {
        VehicleView vehicle = new VehicleView();
        vehicle.setFrameSn(frameSn);
        vehicle.setStatus(status);
        vehicle.setId(id);
        return vehicle;
    }

    private static AssemblyPartRecord part(long id, String sn, String type) {
        AssemblyPartRecord part = new AssemblyPartRecord();
        part.setId(id);
        part.setPartSn(sn);
        part.setPartType(type);
        part.setStatus("AVAILABLE");
        part.setBindStatus("UNBOUND");
        return part;
    }
}
