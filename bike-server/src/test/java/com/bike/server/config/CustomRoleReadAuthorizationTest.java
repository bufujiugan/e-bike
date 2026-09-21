package com.bike.server.config;

import com.bike.server.part.*;
import com.bike.server.vehicle.*;
import com.bike.server.validation.SnRuleService;
import com.bike.server.production.ProductionGuard;
import com.bike.server.trace.TraceService;
import com.bike.server.system.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

@WebMvcTest({PartController.class,VehicleController.class})
@Import({SecurityConfig.class,PartServiceImpl.class,VehicleServiceImpl.class})
class CustomRoleReadAuthorizationTest {
  @Autowired MockMvc mvc;
  @MockitoBean PartMapper parts;
  @MockitoBean VehicleMapper vehicles;
  @MockitoBean SnRuleService snRules;
  @MockitoBean ProductionGuard guard;
  @MockitoBean TraceService trace;
  @MockitoBean VehicleQrCodeService qr;
  @MockitoBean UserMapper users;
  @MockitoBean JwtTokenService tokens;
    @MockitoBean com.bike.server.auth.TokenRevocationMapper revocations;

  @Test void customPartReaderReachesActualService() throws Exception {
    mvc.perform(get("/api/parts").with(user("clerk").authorities(new SimpleGrantedAuthority("ROLE_PART_CLERK"),new SimpleGrantedAuthority("PERM_PART_READ"))))
      .andExpect(status().isOk());
    verify(parts).selectPage(null,null,null,null,0,20);
  }
  @Test void customVehicleReaderReachesActualService() throws Exception {
    mvc.perform(get("/api/vehicles").with(user("clerk").authorities(new SimpleGrantedAuthority("ROLE_VEHICLE_CLERK"),new SimpleGrantedAuthority("PERM_VEHICLE_READ"))))
      .andExpect(status().isOk());
    verify(vehicles).count(null,null,null,null,null,null,null);
  }
  @Test void unrelatedPermissionCannotReadParts() throws Exception {
    mvc.perform(get("/api/parts").with(user("clerk").authorities(new SimpleGrantedAuthority("PERM_ORDER_READ"))))
      .andExpect(status().isForbidden());
    verifyNoInteractions(parts);
  }
  @Test void agentWithExtraReadPermissionStillCannotReadInternalParts() throws Exception {
    mvc.perform(get("/api/parts").with(user("agent").authorities(new SimpleGrantedAuthority("ROLE_WEB_AGENT"),new SimpleGrantedAuthority("PERM_PART_READ"))))
      .andExpect(status().isForbidden());
    verifyNoInteractions(parts);
  }
  @Test void pdaWithExtraReadPermissionStillCannotReadVehicleArchive() throws Exception {
    mvc.perform(get("/api/vehicles").with(user("pda").authorities(new SimpleGrantedAuthority("ROLE_PDA_OPERATOR"),new SimpleGrantedAuthority("PERM_VEHICLE_READ"))))
      .andExpect(status().isForbidden());
    verifyNoInteractions(vehicles);
  }
}
