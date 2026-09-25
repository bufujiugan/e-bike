package com.bike.server.production;

import static com.bike.server.production.ProductionContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bike.server.assembly.*;
import com.bike.server.common.BusinessException;
import com.bike.server.validation.SnRuleServiceImpl;
import com.bike.server.vehicle.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ProductionServiceTest {
  @Test void duplicateReprintCannotQueueTwiceForSameVehicle(){
    when(mapper.selectPrint("job")).thenReturn(Map.of("status","SUBMITTED","vehicleId",10L));
    when(mapper.countActivePrints(10L)).thenReturn(1);
    assertThatThrownBy(()->service.reprint("job","补打")).hasMessageContaining("不能重复创建补打");
  }
  ProductionMapper mapper;
  ProductionServiceImpl service;
  AssemblyService assembly;
  VehicleMapper vehicles;
  Map<String, Object> task;
  List<Map<String, Object>> scans;

  @BeforeEach
  void setup() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "tester", "N/A", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    mapper = mock(ProductionMapper.class);
    assembly = mock(AssemblyService.class);
    vehicles = mock(VehicleMapper.class);
    service =
        new ProductionServiceImpl(
            mapper,
            new ProductionGuard(mapper),
            assembly,
            vehicles,
            new SnRuleServiceImpl(null),
            new ObjectMapper(), mock(ScanFailureService.class));
    task = new HashMap<>(Map.of("id", "task-123456789012", "skuId", 1L, "status", "OPEN"));
    scans = new ArrayList<>();
    when(mapper.selectTask(anyString())).thenReturn(task);
    when(mapper.selectFrame(anyString())).thenReturn(null);
    when(mapper.selectPart(anyString())).thenReturn(null);
    when(mapper.selectReservation(anyString())).thenReturn(null);
    when(mapper.selectScans(anyString())).thenAnswer(call -> new ArrayList<>(scans));
    when(mapper.selectSku(1))
        .thenReturn(Map.of("enabled", true, "modelId", 1L, "templateId", 1L, "skuCode", "SKU-1"));
    when(vehicles.countEnabledModel(1L)).thenReturn(1);
    when(mapper.selectTemplate(1L))
        .thenReturn(Map.of("version", 1, "widthMm", 100, "heightMm", 150));
    when(mapper.insertAudit(anyString(), anyString(), anyString(), any())).thenReturn(1);
    when(mapper.insertScan(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            c -> {
              scans.add(Map.of("codeType", c.getArgument(1), "code", c.getArgument(2)));
              return 1;
            });
    when(mapper.updateTaskStatus(anyString(), eq("COMPLETED"), anyLong()))
        .thenAnswer(
            c -> {
              task.put("status", "COMPLETED");
              return 1;
            });
    for (String type : List.of("MOTOR", "BATTERY", "DISPLAY"))
      when(mapper.selectPart(type + "-1"))
          .thenReturn(
              Map.of(
                  "codeType",
                  type,
                  "status",
                  "AVAILABLE",
                  "bindStatus",
                  "UNBOUND",
                  "qcStatus",
                  "PASSED"));
    VehicleView vehicle = new VehicleView();
    vehicle.setId(10L);
    vehicle.setVehicleQrCode("BIKE:10:TOKEN");
    when(assembly.submitCollected(anyString(), any())).thenReturn(vehicle);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test void successfulScanUpdatesModificationTimeButIdempotentRetryDoesNot() {
    service.scan("task-123456789012",new ScanRequest("MOTOR-1","MOTOR"));
    service.scan("task-123456789012",new ScanRequest("MOTOR-1","MOTOR"));
    verify(mapper,times(1)).touchTask("task-123456789012");
    verify(mapper,times(1)).insertScan("task-123456789012","MOTOR","MOTOR-1","tester");
  }

  static Stream<List<String>> orders() {
    List<List<String>> result = new ArrayList<>();
    permutations(
        new ArrayList<>(),
        new ArrayList<>(List.of("FRAME", "MOTOR", "BATTERY", "DISPLAY")),
        result);
    return result.stream();
  }

  static void permutations(List<String> prefix, List<String> remaining, List<List<String>> result) {
    if (remaining.isEmpty()) {
      result.add(prefix);
      return;
    }
    for (String item : remaining) {
      var next = new ArrayList<>(prefix);
      next.add(item);
      var rest = new ArrayList<>(remaining);
      rest.remove(item);
      permutations(next, rest, result);
    }
  }

  @ParameterizedTest
  @MethodSource("orders")
  void allOrdersCompleteExactlyOnce(List<String> order) {
    for (String type : order)
      service.scan(
          "task-123456789012", new ScanRequest(type + "-1", "FRAME".equals(type) ? type : null));
    assertThat(task.get("status")).isEqualTo("COMPLETED");
    service.scan("task-123456789012", new ScanRequest(order.getLast() + "-1", null));
    service.complete("task-123456789012");
    verify(assembly, times(1))
        .submitCollected(
            "task-123456789012",
            new AssemblySubmitRequest("FRAME-1", "MOTOR-1", "BATTERY-1", "DISPLAY-1"));
    verify(mapper, times(1))
        .insertPrint(
            eq("FIRST-task-123456789012"), eq(10L), anyString(), anyString(), eq("tester"));
  }

  @Test
  void rejectsOtherTaskReservation() {
    when(mapper.selectReservation("MOTOR-1")).thenReturn(Map.of("taskId", "another"));
    assertThatThrownBy(() -> service.scan("task-123456789012", new ScanRequest("MOTOR-1", null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("占用");
    verify(mapper, never()).insertScan(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void duplicateScanDoesNotConsumeAnotherSlot() {
    service.scan("task-123456789012", new ScanRequest("motor-1", null));
    service.scan("task-123456789012", new ScanRequest("MOTOR-1", null));
    assertThat(scans).hasSize(1);
    verifyNoInteractions(assembly);
  }

  @Test
  void failsQcBeforeReservation() {
    when(mapper.selectPart("MOTOR-1"))
        .thenReturn(
            Map.of(
                "codeType",
                "MOTOR",
                "status",
                "AVAILABLE",
                "bindStatus",
                "UNBOUND",
                "qcStatus",
                "FAILED"));
    assertThatThrownBy(() -> service.scan("task-123456789012", new ScanRequest("MOTOR-1", null)))
        .hasMessageContaining("质量放行");
    assertThat(scans).isEmpty();
  }

  @Test
  void stationCannotScanAnotherPartType() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "motor",
                "N/A",
                List.of(
                    new SimpleGrantedAuthority("PERM_ASSEMBLY_OPERATE"),
                    new SimpleGrantedAuthority("WORKSTATION_MOTOR"))));
    assertThatThrownBy(() -> service.scan("task-123456789012", new ScanRequest("BATTERY-1", null)))
        .hasMessageContaining("工位权限");
    assertThat(scans).isEmpty();
  }

  @Test
  void quarantinePreventsFinalization() {
    for (String type : List.of("MOTOR", "BATTERY", "DISPLAY"))
      service.scan("task-123456789012", new ScanRequest(type + "-1", null));
    when(mapper.countOpenIssues("MOTOR-1")).thenReturn(1);
    assertThatThrownBy(() -> service.scan("task-123456789012", new ScanRequest("FRAME-1", "FRAME")))
        .hasMessageContaining("隔离");
    verifyNoInteractions(assembly);
  }

  @Test
  void printerClaimIsExclusive() {
    when(mapper.selectPrint("job"))
        .thenReturn(
            new HashMap<>(
                Map.of(
                    "id", "job", "vehicleId", 10L, "snapshot", "{\"qrCode\":\"BIKE:10:TOKEN\"}")));
    VehicleView vehicle = new VehicleView();
    vehicle.setVehicleQrCode("BIKE:10:TOKEN");
    when(vehicles.selectById(10L)).thenReturn(vehicle);
    when(mapper.updatePrintClaim("job", "terminal", "tester")).thenReturn(0);
    assertThatThrownBy(() -> service.claimPrint("job", "terminal")).hasMessageContaining("禁止重复发送");
  }

  @Test
  void claimedLabelBlocksPartReplacement() {
    when(mapper.countProcessingPrints(10L)).thenReturn(1);
    assertThatThrownBy(() -> new ProductionGuard(mapper).invalidatePendingLabels(10L))
        .hasMessageContaining("正在打印");
    verify(mapper, never()).updateObsoletePrints(anyLong(), anyString());
  }
}
