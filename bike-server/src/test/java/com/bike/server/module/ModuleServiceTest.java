package com.bike.server.module;

import com.bike.server.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;

class ModuleServiceTest {

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void vehicleModuleRequiresVehicleReadPermission() {
        SecurityContextHolder.getContext().setAuthentication(authentication("WEB_OPERATOR", "PERM_DASHBOARD_READ"));
        ModuleMapper mapper = mock(ModuleMapper.class);

        assertThatThrownBy(() -> com.bike.server.common.CustomerScopeTestSupport.isolated(new ModuleServiceImpl(mapper)).list("vehicles", null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("PERMISSION_DENIED");
        verify(mapper, never()).selectRows("vehicles", null, false);
    }

    @Test
    void agentsModuleReturnsMasterDataForMasterReadOperator() {
        SecurityContextHolder.getContext().setAuthentication(authentication("WEB_OPERATOR", "PERM_MASTER_READ"));
        ModuleMapper mapper = mock(ModuleMapper.class);
        when(mapper.countRows(eq("agents"), isNull(), anyList(), isNull(), eq(false))).thenReturn(1L);
        when(mapper.selectRowsPage(eq("agents"), isNull(), anyList(), isNull(), eq("创建时间"), eq(0L), eq(20L), eq(false))).thenReturn(List.of(
                Map.of("代理商编号", "AGT-001", "代理商名称", "Global E-Bike", "联系人", "Bob", "联系电话", "139", "状态", "启用", "创建时间", "2026-09-06 10:00:00", "修改时间", "2026-09-06 11:00:00")
        ));

        ModulePageResponse page = com.bike.server.common.CustomerScopeTestSupport.isolated(new ModuleServiceImpl(mapper)).list("agents", null, 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.columns()).containsExactly("代理商编号", "代理商名称", "联系人", "联系电话", "状态", "创建时间", "修改时间");
        assertThat(page.rows().get(0)).containsExactly("AGT-001", "Global E-Bike", "Bob", "139", "启用", "2026-09-06 10:00:00", "2026-09-06 11:00:00");
    }

    @Test
    void locationsModuleCanBeReadByMasterOperatorButRemainsReadOnlyInWeb() {
        SecurityContextHolder.getContext().setAuthentication(authentication("WEB_OPERATOR", "PERM_MASTER_READ"));
        ModuleMapper mapper = mock(ModuleMapper.class);
        when(mapper.countRows(eq("locations"), isNull(), anyList(), isNull(), eq(false))).thenReturn(1L);
        when(mapper.selectRowsPage(eq("locations"), isNull(), anyList(), isNull(), eq("创建时间"), eq(0L), eq(20L), eq(false))).thenReturn(List.of(
                Map.of("仓库", "成品仓", "仓库编码", "FG-01", "库位", "A区一号位", "库位编码", "A-01-01", "状态", "启用", "创建时间", "2026-09-06 10:00:00", "修改时间", "2026-09-06 11:00:00")
        ));

        ModulePageResponse page = com.bike.server.common.CustomerScopeTestSupport.isolated(new ModuleServiceImpl(mapper)).list("locations", null, 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.rows().get(0)).containsExactly("成品仓", "FG-01", "A区一号位", "A-01-01", "启用", "2026-09-06 10:00:00", "2026-09-06 11:00:00");
    }

    @Test
    void stockInAndStockOutModulesKeepTheirRecordTypesSeparate() {
        SecurityContextHolder.getContext().setAuthentication(authentication("WEB_OPERATOR", "PERM_STOCK_READ"));
        ModuleMapper mapper = mock(ModuleMapper.class);
        when(mapper.countRows(eq("stock-in"), isNull(), anyList(), isNull(), eq(false))).thenReturn(1L);
        when(mapper.selectRowsPage(eq("stock-in"), isNull(), anyList(), isNull(), eq("创建时间"), eq(0L), eq(20L), eq(false))).thenReturn(List.of(
                Map.of("业务单号", "IN-1", "类型", "整车入库", "整车数量", "1 台", "客户 / 仓库", "成品仓", "操作人", "admin", "操作时间", "2026-09-06", "状态", "已完成")
        ));
        when(mapper.countRows(eq("stock-out"), isNull(), anyList(), isNull(), eq(false))).thenReturn(1L);
        when(mapper.selectRowsPage(eq("stock-out"), isNull(), anyList(), isNull(), eq("创建时间"), eq(0L), eq(20L), eq(false))).thenReturn(List.of(
                Map.of("业务单号", "OUT-1", "类型", "整车出库", "整车数量", "1 台", "客户 / 仓库", "客户 A", "操作人", "admin", "操作时间", "2026-09-06", "状态", "已完成")
        ));

        ModuleService service = com.bike.server.common.CustomerScopeTestSupport.isolated(new ModuleServiceImpl(mapper));
        assertThat(service.list("stock-in", null, 1, 20).rows().get(0).get(1)).isEqualTo("整车入库");
        assertThat(service.list("stock-out", null, 1, 20).rows().get(0).get(1)).isEqualTo("整车出库");
        verify(mapper).selectRowsPage(eq("stock-in"), isNull(), anyList(), isNull(), eq("创建时间"), eq(0L), eq(20L), eq(false));
        verify(mapper).selectRowsPage(eq("stock-out"), isNull(), anyList(), isNull(), eq("创建时间"), eq(0L), eq(20L), eq(false));
        verify(mapper, never()).selectRows(anyString(), any(), anyBoolean());
    }

    @Test
    void ordersModuleSortsByCreationTimeDescending() {
        SecurityContextHolder.getContext().setAuthentication(authentication("WEB_OPERATOR", "PERM_ORDER_READ"));
        ModuleMapper mapper = mock(ModuleMapper.class);
        when(mapper.countRows(eq("orders"), isNull(), anyList(), isNull(), eq(false))).thenReturn(1L);
        when(mapper.selectRowsPage(eq("orders"), isNull(), anyList(), isNull(), eq("创建时间"), eq(0L), eq(20L), eq(false))).thenReturn(List.of(
                Map.ofEntries(
                        Map.entry("订单号", "SO-1"), Map.entry("客户", "客户 A"), Map.entry("车型", "车型 A"),
                        Map.entry("订单数量", "1 台"), Map.entry("已出库", "0 台"), Map.entry("计划发货", "2026-10-20"),
                        Map.entry("状态", "待出库"), Map.entry("来源锁定", "LK-1"), Map.entry("占用截止", "2026-10-20 12:00:00"),
                        Map.entry("占用状态", "有效"), Map.entry("备注", "-"), Map.entry("创建时间", "2026-10-16 10:00:00"),
                        Map.entry("修改时间", "2026-10-16 11:00:00")
                )
        ));

        ModulePageResponse page = com.bike.server.common.CustomerScopeTestSupport.isolated(new ModuleServiceImpl(mapper)).list("orders", null, 1, 20);

        assertThat(page.columns()).endsWith("创建时间", "修改时间");
        assertThat(page.rows().getFirst()).endsWith("2026-10-16 10:00:00", "2026-10-16 11:00:00");
        verify(mapper).selectRowsPage(eq("orders"), isNull(), anyList(), isNull(), eq("创建时间"), eq(0L), eq(20L), eq(false));
    }

    @Test
    void listFormatsJdbcAndJavaTimesWithSecondsWithoutChangingDates() {
        SecurityContextHolder.getContext().setAuthentication(authentication("WEB_OPERATOR", "PERM_ORDER_READ"));
        ModuleMapper mapper = mock(ModuleMapper.class);
        when(mapper.selectRowsPage(eq("orders"), isNull(), anyList(), isNull(), eq("创建时间"), eq(0L), eq(20L), eq(false)))
                .thenReturn(List.of(Map.of("创建时间", java.time.LocalDateTime.of(2026, 10, 16, 9, 5),
                        "修改时间", java.sql.Timestamp.valueOf("2026-10-16 10:06:07.123"),
                        "计划发货", java.sql.Date.valueOf("2026-10-20"))));
        var page = com.bike.server.common.CustomerScopeTestSupport.isolated(new ModuleServiceImpl(mapper)).list("orders", null, 1, 20);
        assertThat(page.rows().getFirst()).endsWith("2026-10-16 09:05:00", "2026-10-16 10:06:07");
        assertThat(page.rows().getFirst().get(5)).isEqualTo("2026-10-20");
    }

    private static UsernamePasswordAuthenticationToken authentication(String role, String... permissions) {
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        for (String permission : permissions) authorities.add(new SimpleGrantedAuthority(permission));
        return UsernamePasswordAuthenticationToken.authenticated("test-user", null, authorities);
    }
}
