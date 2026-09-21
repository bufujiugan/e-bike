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

        assertThatThrownBy(() -> new ModuleServiceImpl(mapper).list("vehicles", null, 1, 20))
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
        when(mapper.selectRowsPage(eq("agents"), isNull(), anyList(), isNull(), eq("更新时间"), eq(0L), eq(20L), eq(false))).thenReturn(List.of(
                Map.of("代理商编号", "AGT-001", "代理商名称", "Global E-Bike", "联系人", "Bob", "联系电话", "139", "状态", "启用", "更新时间", "2026-09-06")
        ));

        ModulePageResponse page = new ModuleServiceImpl(mapper).list("agents", null, 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.columns()).containsExactly("代理商编号", "代理商名称", "联系人", "联系电话", "状态", "更新时间");
        assertThat(page.rows().get(0)).containsExactly("AGT-001", "Global E-Bike", "Bob", "139", "启用", "2026-09-06");
    }

    @Test
    void locationsModuleCanBeReadByMasterOperatorButRemainsReadOnlyInWeb() {
        SecurityContextHolder.getContext().setAuthentication(authentication("WEB_OPERATOR", "PERM_MASTER_READ"));
        ModuleMapper mapper = mock(ModuleMapper.class);
        when(mapper.countRows(eq("locations"), isNull(), anyList(), isNull(), eq(false))).thenReturn(1L);
        when(mapper.selectRowsPage(eq("locations"), isNull(), anyList(), isNull(), eq("更新时间"), eq(0L), eq(20L), eq(false))).thenReturn(List.of(
                Map.of("仓库", "成品仓", "仓库编码", "FG-01", "库位", "A区一号位", "库位编码", "A-01-01", "状态", "启用", "更新时间", "2026-09-06")
        ));

        ModulePageResponse page = new ModuleServiceImpl(mapper).list("locations", null, 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.rows().get(0)).containsExactly("成品仓", "FG-01", "A区一号位", "A-01-01", "启用", "2026-09-06");
    }

    @Test
    void stockInAndStockOutModulesKeepTheirRecordTypesSeparate() {
        SecurityContextHolder.getContext().setAuthentication(authentication("WEB_OPERATOR", "PERM_STOCK_READ"));
        ModuleMapper mapper = mock(ModuleMapper.class);
        when(mapper.countRows(eq("stock-in"), isNull(), anyList(), isNull(), eq(false))).thenReturn(1L);
        when(mapper.selectRowsPage(eq("stock-in"), isNull(), anyList(), isNull(), eq("操作时间"), eq(0L), eq(20L), eq(false))).thenReturn(List.of(
                Map.of("业务单号", "IN-1", "类型", "整车入库", "整车数量", "1 台", "客户 / 仓库", "成品仓", "操作人", "admin", "操作时间", "2026-09-06", "状态", "已完成")
        ));
        when(mapper.countRows(eq("stock-out"), isNull(), anyList(), isNull(), eq(false))).thenReturn(1L);
        when(mapper.selectRowsPage(eq("stock-out"), isNull(), anyList(), isNull(), eq("操作时间"), eq(0L), eq(20L), eq(false))).thenReturn(List.of(
                Map.of("业务单号", "OUT-1", "类型", "整车出库", "整车数量", "1 台", "客户 / 仓库", "客户 A", "操作人", "admin", "操作时间", "2026-09-06", "状态", "已完成")
        ));

        ModuleService service = new ModuleServiceImpl(mapper);
        assertThat(service.list("stock-in", null, 1, 20).rows().get(0).get(1)).isEqualTo("整车入库");
        assertThat(service.list("stock-out", null, 1, 20).rows().get(0).get(1)).isEqualTo("整车出库");
        verify(mapper).selectRowsPage(eq("stock-in"), isNull(), anyList(), isNull(), eq("操作时间"), eq(0L), eq(20L), eq(false));
        verify(mapper).selectRowsPage(eq("stock-out"), isNull(), anyList(), isNull(), eq("操作时间"), eq(0L), eq(20L), eq(false));
        verify(mapper, never()).selectRows(anyString(), any(), anyBoolean());
    }

    private static UsernamePasswordAuthenticationToken authentication(String role, String... permissions) {
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        for (String permission : permissions) authorities.add(new SimpleGrantedAuthority(permission));
        return UsernamePasswordAuthenticationToken.authenticated("test-user", null, authorities);
    }
}
