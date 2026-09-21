package com.bike.server.production;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bike.server.assembly.AssemblySubmitRequest;
import com.bike.server.validation.SnRuleServiceImpl;
import com.bike.server.vehicle.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class LegacyAssemblyServiceTest {
  ProductionService production = mock(ProductionService.class);
  ProductionMapper mapper = mock(ProductionMapper.class);
  VehicleMapper vehicles = mock(VehicleMapper.class);
  LegacyAssemblyService service = new LegacyAssemblyService(production, new ProductionGuard(mapper), mapper, vehicles, new SnRuleServiceImpl(null));
  AssemblySubmitRequest request = new AssemblySubmitRequest("FRAME-1", "MOTOR-1", "BATTERY-1", "DISPLAY-1");
  VehicleView vehicle = new VehicleView();

  @BeforeEach void setup() {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("tester", "N/A", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    vehicle.setModelId(1L); vehicle.setStatus(0);
    when(vehicles.selectByFrameForUpdate("FRAME-1")).thenReturn(vehicle);
    when(vehicles.selectByFrame("FRAME-1")).thenReturn(vehicle);
    when(mapper.selectSkus()).thenReturn(List.of(Map.of("id", 7L, "modelId", 1L, "enabled", true)));
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  @Test void uniqueSkuUsesUnifiedTaskAndFourScans() {
    assertThat(service.submit(request)).isSameAs(vehicle);
    verify(production).createTask(argThat(r -> r.skuId()==7 && r.requestId().startsWith("LEGACY-")));
    for (String type: List.of("FRAME", "MOTOR", "BATTERY", "DISPLAY"))
      verify(production).scan(anyString(), eq(new ProductionContracts.ScanRequest(type+"-1",type)));
  }
  @Test void missingSkuRejectsWithoutProductionWrites() {
    when(mapper.selectSkus()).thenReturn(List.of());
    assertThatThrownBy(() -> service.submit(request)).hasMessageContaining("唯一启用SKU");
    verifyNoInteractions(production);
  }
  @Test void ambiguousSkuRejectsWithoutChoosingFirst() {
    when(mapper.selectSkus()).thenReturn(List.of(Map.of("id",7L,"modelId",1L,"enabled",true), Map.of("id",8L,"modelId",1L,"enabled",1)));
    assertThatThrownBy(() -> service.submit(request)).hasMessageContaining("唯一启用SKU");
    verifyNoInteractions(production);
  }
  void completed(String motor) {
    vehicle.setStatus(1);
    when(mapper.selectTask(anyString())).thenReturn(Map.of("status","COMPLETED"));
    when(mapper.selectScans(anyString())).thenReturn(List.of(Map.of("codeType","FRAME","code","FRAME-1"), Map.of("codeType","MOTOR","code",motor), Map.of("codeType","BATTERY","code","BATTERY-1"), Map.of("codeType","DISPLAY","code","DISPLAY-1")));
  }
  @Test void completedRetryDoesNotRescanOrReprint() {
    completed("MOTOR-1");
    assertThat(service.submit(request)).isSameAs(vehicle);
    assertThat(service.submit(request)).isSameAs(vehicle);
    verifyNoInteractions(production);
  }
  @Test void completedTaskCannotChangeCodes() {
    completed("MOTOR-2");
    assertThatThrownBy(() -> service.submit(request)).hasMessageContaining("四码不能更改");
    verifyNoInteractions(production);
  }
  @Test void incompleteHistoryCannotPassAsIdempotentSuccess() {
    completed("MOTOR-1");
    when(mapper.selectScans(anyString())).thenReturn(List.of());
    assertThatThrownBy(() -> service.submit(request)).hasMessageContaining("四码不能更改");
    verifyNoInteractions(production);
  }
}
