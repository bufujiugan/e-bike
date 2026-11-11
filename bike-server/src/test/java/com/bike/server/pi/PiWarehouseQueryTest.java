package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisXMLMapperBuilder;
import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.*;
import com.bike.server.order.OrderReservationCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.IntStream;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PiWarehouseQueryTest {
    private final PiMapper mapper = mock(PiMapper.class);
    private final PiServiceImpl service = CustomerScopeTestSupport.isolated(new PiServiceImpl(mapper,
            mock(PiDocumentService.class), mock(OperationLogMapper.class), new ObjectMapper().findAndRegisterModules(),
            Validation.buildDefaultValidatorFactory().getValidator(), mock(OrderReservationCoordinator.class)));

    @BeforeEach void login() { authenticate("ROLE_SUPER_ADMIN"); }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    @Test void filteredListKeepsWarehouseAcrossPaginationAndCounts() {
        when(mapper.countByWarehouse("PI-CZ", "CONFIRMED", null, 23L)).thenReturn(41L);
        var result = service.listByWarehouse(" PI-CZ ", "confirmed", 23L, 2, 20);
        verify(mapper).selectPageByWarehouse("PI-CZ", "CONFIRMED", null, 23L, 20L, 20L);
        assertThat(result.total()).isEqualTo(41);
        verify(mapper, never()).selectPage(any(), any(), any(), anyLong(), anyLong());
    }

    @Test void originalListRemainsUnfiltered() {
        service.list(null, null, 1, 20);
        verify(mapper).selectPage(null, null, null, 0, 20);
        verify(mapper).count(null, null, null);
        verify(mapper, never()).selectPageByWarehouse(any(), any(), any(), anyLong(), anyLong(), anyLong());
    }

    @Test void invalidWarehouseNeverFallsBackToAllOrders() {
        assertThatThrownBy(() -> service.listByWarehouse(null, null, 0L, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_PARAMETER"));
        verifyNoInteractions(mapper);
    }

    @Test void warehouseOptionsAllowReadOnlyStaffButRejectExternalAccounts() {
        authenticate("ROLE_WEB_OPERATOR", "PERM_PI_READ");
        service.listWarehouseOptions();
        verify(mapper).selectWarehouseOptions();
        clearInvocations(mapper);
        for (String role : List.of("ROLE_WEB_AGENT", "ROLE_PDA_OPERATOR", "ROLE_WEB_CUSTOMER")) {
            authenticate(role, "PERM_PI_READ");
            assertThatThrownBy(service::listWarehouseOptions).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(mapper);
    }

    @Test void warehouseFilterDoesNotBypassAgentScopeOrFinancialRedaction() {
        authenticate("ROLE_WEB_AGENT", "PERM_PI_READ");
        var auth = (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        auth.setDetails(new UserAuthRecord(3L, "agent", "", "WEB_AGENT", 9L, null, true));
        var pi = new PiView(); pi.setId(1L); pi.setPaidAmount(new BigDecimal("50")); pi.setPaymentStatus("PARTIAL");
        when(mapper.selectPageByWarehouse(null, null, 9L, 23L, 0, 20)).thenReturn(List.of(pi));
        var result = service.listByWarehouse(null, null, 23L, 1, 20);
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().getPaidAmount()).isNull();
        assertThat(result.records().getFirst().getPaymentStatus()).isNull();
        verify(mapper).countByWarehouse(null, null, 9L, 23L);
    }

    @Test void exportUsesSelectedWarehouseForEveryPage() throws Exception {
        var pis = mock(PiService.class); var audit = mock(OperationLogMapper.class);
        when(audit.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        var reports = new PiReportService(mock(PiReportMapper.class), pis, audit, mock(CustomerScopeAccess.class));
        var records = IntStream.rangeClosed(1, 101).mapToObj(index -> {
            var pi = new PiView(); pi.setPiNo("CZ-" + index); pi.setRevision(1); return pi;
        }).toList();
        when(pis.listByWarehouse(null, null, 23L, 1, 100)).thenReturn(new PageResponse<>(records.subList(0,100), 1,100,101));
        when(pis.listByWarehouse(null, null, 23L, 2, 100)).thenReturn(new PageResponse<>(records.subList(100,101), 2,100,101));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(reports.export(null,null,23L)))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(101);
            assertThat(workbook.getSheetAt(0).getRow(101).getCell(0).getStringCellValue()).isEqualTo("CZ-101");
        }
        verify(pis, never()).list(any(), any(), anyLong(), anyLong());
    }

    @Test void mapperSqlCombinesWarehouseWithExistingCustomerAndAgentRestrictions() throws Exception {
        var config = new MybatisConfiguration();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("file:src/main/resources/mapper/*.xml")) {
            try (var stream = resource.getInputStream()) {
                new MybatisXMLMapperBuilder(stream, config, resource.toString(), config.getSqlFragments()).parse();
            }
        }
        authenticate("ROLE_WEB_OPERATOR", "PERM_PI_READ");
        var params = new MapperMethod.ParamMap<Object>();
        params.put("keyword", "CZ"); params.put("status", "CONFIRMED"); params.put("agentId", 9L);
        params.put("warehouseId", 23L); params.put("offset", 20L); params.put("limit", 20L);
        for (String statement : List.of("selectPageByWarehouse", "countByWarehouse")) {
            var bound = config.getMappedStatement("com.bike.server.pi.PiMapper." + statement).getBoundSql(params);
            assertThat(bound.getSql()).contains("$.business.warehouseId", "bike_user_customer_scope", "p.agent_id=", "p.status=");
            assertThat(bound.getParameterMappings()).anyMatch(parameter -> parameter.getProperty().equals("warehouseId"));
        }
        params.remove("warehouseId");
        assertThat(config.getMappedStatement("com.bike.server.pi.PiMapper.selectPage").getBoundSql(params).getSql())
                .doesNotContain("$.business.warehouseId");
    }

    private static void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("warehouse-test", null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
