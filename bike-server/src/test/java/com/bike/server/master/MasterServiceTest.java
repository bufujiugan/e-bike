package com.bike.server.master;

import com.bike.server.common.BusinessException;
import com.bike.server.common.OperationLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasterServiceTest {
    private MasterMapper mapper;
    private OperationLogMapper operationLogMapper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", "N/A", java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        mapper = Mockito.mock(MasterMapper.class);
        operationLogMapper = Mockito.mock(OperationLogMapper.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateLocationNormalizesCodeAndKeepsWarehouse() {
        LocationItem before = new LocationItem(9L, 3L, "A-01", "旧库位", "ENABLED");
        LocationItem updated = new LocationItem(9L, 3L, "A-02", "新库位", "ENABLED");
        Mockito.when(mapper.selectLocationById(9L)).thenReturn(before, updated);
        Mockito.when(mapper.selectLocationByCode(3L, "A-02")).thenReturn(null);
        Mockito.when(mapper.updateLocation(9L, new LocationUpdateRequest("A-02", "新库位"))).thenReturn(1);

        LocationItem result = new MasterServiceImpl(mapper, operationLogMapper)
                .updateLocation(9L, new LocationUpdateRequest(" a-02 ", " 新库位 "));

        assertThat(result).isEqualTo(updated);
        Mockito.verify(mapper).updateLocation(9L, new LocationUpdateRequest("A-02", "新库位"));
    }

    @Test
    void enableLocationRejectsDisabledWarehouse() {
        LocationItem location = new LocationItem(9L, 3L, "A-01", "库位", "DISABLED");
        MasterItem warehouse = new MasterItem(3L, "WH-01", "成品仓", null, null, "DISABLED", null);
        Mockito.when(mapper.selectLocationById(9L)).thenReturn(location);
        Mockito.when(mapper.selectById("warehouses", 3L)).thenReturn(warehouse);

        assertThatThrownBy(() -> new MasterServiceImpl(mapper, operationLogMapper).updateLocationStatus(9L, true))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("WAREHOUSE_DISABLED");
        Mockito.verify(mapper, Mockito.never()).updateLocationStatus(Mockito.anyLong(), Mockito.anyBoolean());
    }
}
