package com.bike.server.production;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.bike.server.config.*;
import com.bike.server.system.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductionController.class)
@Import(SecurityConfig.class)
class ProductionValidationTest {
  @Autowired MockMvc mvc;
  @MockitoBean ProductionService production;
  @MockitoBean ProductionLabelService labels;
  @MockitoBean ProductionAttachmentService attachments;
  @MockitoBean ProductionExcelService excel;
  @MockitoBean ScanFailureService failures;
  @MockitoBean UserMapper users;
  @MockitoBean JwtTokenService tokens;
    @MockitoBean com.bike.server.auth.TokenRevocationMapper revocations;

  @Test void invalidScanTypeIsAuditedBeforeServiceInvocation() throws Exception {
    mvc.perform(post("/api/production/tasks/task-123456789012/scan").with(user("operator").roles("SUPER_ADMIN"))
        .contentType("application/json").content("{\"code\":\"MOTOR-1\",\"expectedType\":\"INVALID\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    verify(failures).record(eq("task-123456789012"),argThat(r->r.code().equals("MOTOR-1") && r.expectedType().equals("INVALID")),argThat(e->e.getCode().equals("INVALID_PARAMETER")));
    verifyNoInteractions(production);
  }
  @Test void missingCodeIsAuditedAsValidationFailure() throws Exception {
    mvc.perform(post("/api/production/tasks/task-123456789012/scan").with(user("operator").roles("SUPER_ADMIN"))
        .contentType("application/json").content("{\"expectedType\":\"MOTOR\"}"))
        .andExpect(status().isBadRequest());
    verify(failures).record(eq("task-123456789012"),argThat(r->r.code()==null),any());
    verifyNoInteractions(production);
  }
  @Test void nonScanValidationDoesNotCreateScanAudit() throws Exception {
    mvc.perform(post("/api/production/tasks").with(user("operator").roles("SUPER_ADMIN"))
        .contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(failures,production);
  }
}
