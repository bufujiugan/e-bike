package com.bike.server.system;

import com.bike.server.common.BusinessException;
import com.bike.server.common.OperationLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class WorkstationServiceTest {
    private WorkstationMapper mapper;
    private OperationLogMapper operationLogMapper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", "N/A", java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        mapper = Mockito.mock(WorkstationMapper.class);
        operationLogMapper = Mockito.mock(OperationLogMapper.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createNormalizesSupportedWorkstationType() {
        WorkstationView created = workstation(8L, "WS-IN", "STOCK_IN");
        Mockito.when(mapper.selectByCode("WS-IN")).thenReturn(null, created);
        Mockito.when(mapper.insert(new WorkstationRequest("WS-IN", "入库工位", "STOCK_IN"))).thenReturn(1);

        WorkstationView result = new WorkstationServiceImpl(mapper, operationLogMapper)
                .create(new WorkstationRequest(" ws-in ", " 入库工位 ", " stock_in "));

        assertThat(result.getWorkstationType()).isEqualTo("STOCK_IN");
        Mockito.verify(mapper).insert(new WorkstationRequest("WS-IN", "入库工位", "STOCK_IN"));
    }

    @Test
    void rejectUnsupportedWorkstationTypeBeforeDatabaseWrite() {
        assertThatThrownBy(() -> new WorkstationServiceImpl(mapper, operationLogMapper)
                .create(new WorkstationRequest("WS-LAMP", "车灯工位", "LAMP")))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("WORKSTATION_TYPE_INVALID");
        Mockito.verifyNoInteractions(mapper, operationLogMapper);
    }

    @Test
    void acceptsComponentWorkstationTypesDefinedByBusinessSpecification() {
        WorkstationView created = workstation(18L, "WS-MOTOR", "MOTOR");
        Mockito.when(mapper.selectByCode("WS-MOTOR")).thenReturn(null, created);
        Mockito.when(mapper.insert(new WorkstationRequest("WS-MOTOR", "电机工位", "MOTOR"))).thenReturn(1);

        WorkstationView result = new WorkstationServiceImpl(mapper, operationLogMapper)
                .create(new WorkstationRequest(" ws-motor ", " 电机工位 ", " motor "));

        assertThat(result.getWorkstationType()).isEqualTo("MOTOR");
        Mockito.verify(mapper).insert(new WorkstationRequest("WS-MOTOR", "电机工位", "MOTOR"));
    }

    private static WorkstationView workstation(long id, String code, String type) {
        WorkstationView view = new WorkstationView();
        view.setId(id);
        view.setWorkstationCode(code);
        view.setWorkstationName("入库工位");
        view.setWorkstationType(type);
        view.setEnabled(true);
        return view;
    }
}
