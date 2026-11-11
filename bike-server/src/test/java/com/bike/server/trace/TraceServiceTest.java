package com.bike.server.trace;

import com.bike.server.common.BusinessException;
import com.bike.server.vehicle.VehicleView;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void setAuthenticatedAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-admin", "N/A", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }


    @Test
    void frameTraceReturnsStructuredStockAndReworkHistory() {
        TraceMapper mapper = Mockito.mock(TraceMapper.class);
        VehicleView vehicle = new VehicleView();
        vehicle.setFrameSn("FRAME-1");
        Mockito.when(mapper.selectVehicleByFrame("FRAME-1")).thenReturn(vehicle);
        Mockito.when(mapper.selectBindingHistory("FRAME-1")).thenReturn(List.of());
        Mockito.when(mapper.selectEvents("FRAME-1")).thenReturn(List.of());
        Mockito.when(mapper.selectStockInRecords("FRAME-1")).thenReturn(List.of(new TraceStockInRecord()));
        Mockito.when(mapper.selectStockOutRecords("FRAME-1")).thenReturn(List.of(new TraceStockOutRecord()));
        Mockito.when(mapper.selectReworkRecords("FRAME-1")).thenReturn(List.of(new TraceReworkRecord()));

        TraceResponse response = new TraceServiceImpl(mapper).traceFrame(" FRAME-1 ");

        assertThat(response.stockInRecords()).hasSize(1);
        assertThat(response.stockOutRecords()).hasSize(1);
        assertThat(response.reworkRecords()).hasSize(1);
    }

    @Test
    void partTraceIncludesAllVehiclesThatHeldThePart() {
        TraceMapper mapper = Mockito.mock(TraceMapper.class);
        VehicleView vehicle = new VehicleView();
        vehicle.setFrameSn("FRAME-1");
        Mockito.when(mapper.selectFrameByPart("PART-1")).thenReturn("FRAME-1");
        Mockito.when(mapper.selectVehicleByFrame("FRAME-1")).thenReturn(vehicle);
        Mockito.when(mapper.selectVehiclesByPart("PART-1")).thenReturn(List.of(vehicle, new VehicleView()));
        Mockito.when(mapper.selectBindingHistory("FRAME-1")).thenReturn(List.of());
        Mockito.when(mapper.selectEvents("FRAME-1")).thenReturn(List.of());
        List<TraceStockInRecord> stockInRecords = List.of(new TraceStockInRecord());
        List<TraceStockOutRecord> stockOutRecords = List.of(new TraceStockOutRecord());
        List<TraceReworkRecord> reworkRecords = List.of(new TraceReworkRecord());
        Mockito.when(mapper.selectStockInRecords("FRAME-1")).thenReturn(stockInRecords);
        Mockito.when(mapper.selectStockOutRecords("FRAME-1")).thenReturn(stockOutRecords);
        Mockito.when(mapper.selectReworkRecords("FRAME-1")).thenReturn(reworkRecords);
        Mockito.when(mapper.selectStockInRecordsByPart("PART-1")).thenReturn(stockInRecords);
        Mockito.when(mapper.selectStockOutRecordsByPart("PART-1")).thenReturn(stockOutRecords);
        Mockito.when(mapper.selectReworkRecordsByPart("PART-1")).thenReturn(reworkRecords);

        TraceResponse response = new TraceServiceImpl(mapper).tracePart("PART-1");

        assertThat(response.relatedVehicles()).hasSize(2);
        assertThat(response.stockInRecords()).isSameAs(stockInRecords);
        assertThat(response.stockOutRecords()).isSameAs(stockOutRecords);
        assertThat(response.reworkRecords()).isSameAs(reworkRecords);
    }

    @Test
    void frameTraceLoadsPurchaseContractsForOutboundRecordsInOneBatch() {
        var mapper = Mockito.mock(TraceMapper.class);
        var contracts = Mockito.mock(com.bike.server.pi.PiPurchaseContractService.class);
        var service = new TraceServiceImpl(mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "purchaseContracts", contracts);
        var vehicle = new VehicleView();
        vehicle.setFrameSn("FRAME-1");
        var first = new TraceStockOutRecord();
        first.setStockOutNo("OUT-1");
        var second = new TraceStockOutRecord();
        second.setStockOutNo("OUT-2");
        Mockito.when(mapper.selectVehicleByFrame("FRAME-1")).thenReturn(vehicle);
        Mockito.when(mapper.selectStockOutRecords("FRAME-1")).thenReturn(List.of(first, second));
        Mockito.when(contracts.findForStockRecords(List.of("OUT-1", "OUT-2"))).thenReturn(java.util.Map.of(
                "OUT-1", new com.bike.server.pi.PiPurchaseContract("SAVA", "BUY-OLD"),
                "OUT-2", new com.bike.server.pi.PiPurchaseContract("YINSANHUAN", "BUY-NEW")));
        var response = service.traceFrame("FRAME-1");
        assertThat(response.stockOutRecords()).extracting(TraceStockOutRecord::getPurchaseContractNo)
                .containsExactly("BUY-OLD", "BUY-NEW");
        Mockito.verify(contracts).findForStockRecords(List.of("OUT-1", "OUT-2"));
    }

    @Test
    void traceRejectsBlankIdentifierWithBusinessCode() {
        assertThatThrownBy(() -> new TraceServiceImpl(Mockito.mock(TraceMapper.class)).traceFrame("  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
    }
}
