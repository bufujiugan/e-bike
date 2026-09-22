package com.bike.server.aftersales;

import com.bike.server.auth.TokenRevocationMapper;
import com.bike.server.config.JwtTokenService;
import com.bike.server.config.SecurityConfig;
import com.bike.server.system.UserMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AfterSalesController.class)
@Import({SecurityConfig.class, AfterSalesServiceImpl.class})
class AfterSalesHttpAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean AfterSalesMapper mapper;
    @MockitoBean UserMapper users;
    @MockitoBean JwtTokenService tokens;
    @MockitoBean TokenRevocationMapper revocations;

    @Test void advancedQueryBindsDatesAndDashboardUsesTheSameNormalizedCriteria() throws Exception {
        when(mapper.selectFilteredPage(any(), isNull(), isNull(), any(), anyLong(), anyLong())).thenReturn(List.of());
        when(mapper.selectDashboard(any(), isNull(), isNull(), any())).thenReturn(new AfterSalesDashboardView(0,0,0,0,0,0,0,0,0));
        mvc.perform(get("/api/after-sales/cases").with(user("admin").roles("SUPER_ADMIN"))
            .param("companyName"," Bikes ").param("countryCode","gb").param("createdTo","2026-09-20").param("queue","WAITING_SHIPMENT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        verify(mapper).selectFilteredPage(argThat(f -> "Bikes".equals(f.companyName()) && "GB".equals(f.countryCode())
            && java.time.LocalDate.of(2026,9,21).equals(f.createdBefore()) && "WAITING_SHIPMENT".equals(f.queue())), isNull(), isNull(), any(), eq(0L), eq(20L));
        mvc.perform(get("/api/after-sales/cases/dashboard").with(user("admin").roles("SUPER_ADMIN"))
            .param("status","CLOSED").param("queue","COMPLETED_MONTH").param("countryCode","gb"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        verify(mapper).selectDashboard(argThat(f -> f.status()==null && f.queue()==null && "GB".equals(f.countryCode())),isNull(),isNull(),any());
    }

    @Test void dashboardRejectsPdaMissingReadPermissionAndMalformedDates() throws Exception {
        mvc.perform(get("/api/after-sales/cases/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/after-sales/cases/dashboard").with(user("pda").roles("PDA_OPERATOR","SUPER_ADMIN")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/after-sales/cases/dashboard").with(user("reader").roles("WEB_OPERATOR")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/after-sales/cases").with(user("admin").roles("SUPER_ADMIN")).param("createdTo","not-a-date"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @Test void anonymousAndPdaCannotReadAfterSales() throws Exception {
        mvc.perform(get("/api/after-sales/cases")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/after-sales/cases").with(user("pda").authorities(
            new SimpleGrantedAuthority("ROLE_PDA_OPERATOR"), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        verifyNoInteractions(mapper);
    }

    @Test void readOnlyAccountCannotPostAMessage() throws Exception {
        mvc.perform(post("/api/after-sales/cases/1/messages").with(user("reader").authorities(
            new SimpleGrantedAuthority("PERM_AFTER_SALES_READ"))).contentType("application/json")
            .content("{\"body\":\"no write permission\",\"visibility\":\"CUSTOMER\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        verifyNoInteractions(mapper);
    }

    @Test void malformedVisibilityAndQuantityAreNotPersisted() throws Exception {
        when(mapper.selectForUpdate(1)).thenReturn(AfterSalesServiceTest.caseRow("IN_PROGRESS"));
        mvc.perform(post("/api/after-sales/cases/1/messages").with(user("admin").roles("SUPER_ADMIN"))
            .contentType("application/json").content("{\"body\":\"x\",\"visibility\":\"EVERYONE\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(post("/api/after-sales/cases/1/shipments").with(user("admin").roles("SUPER_ADMIN"))
            .contentType("application/json").content("{\"partName\":\"motor\",\"quantity\":0}"))
            .andExpect(status().isBadRequest());
        verify(mapper, never()).insertMessage(anyLong(), any(), any(), any());
        verify(mapper, never()).insertShipment(anyLong(), any(), any());
    }

    @Test void agentRoleHasPriorityOverAccidentalAdminForInternalEndpoints() throws Exception {
        for (String path : List.of("process", "resolve")) {
            mvc.perform(post("/api/after-sales/cases/1/" + path).with(user("mixed").roles("WEB_AGENT", "SUPER_ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(post("/api/after-sales/cases/1/solution").with(user("mixed").roles("WEB_AGENT", "SUPER_ADMIN"))
            .contentType("application/json").content("{\"solution\":\"test\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(mapper);
    }

    @Test void customTechnicalRoleCanSeeInternalNoteAndReadOnlyRoleCannot() throws Exception {
        var row = AfterSalesServiceTest.caseRow("UNDER_REVIEW"); row.setInternalNote("private");
        when(mapper.selectById(1)).thenReturn(row);
        mvc.perform(get("/api/after-sales/cases/1").with(user("tech").authorities(
            new SimpleGrantedAuthority("ROLE_CUSTOM_TECHNICIAN"), new SimpleGrantedAuthority("PERM_AFTER_SALES_READ"),
            new SimpleGrantedAuthority("PERM_AFTER_SALES_TECH"),new SimpleGrantedAuthority("PERM_AFTER_SALES_SCOPE_ALL"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.internalNote").value("private"));
        mvc.perform(get("/api/after-sales/cases/1").with(user("reader").authorities(new SimpleGrantedAuthority("PERM_AFTER_SALES_READ"),new SimpleGrantedAuthority("PERM_AFTER_SALES_SCOPE_ALL"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.internalNote").doesNotExist());
    }
}
