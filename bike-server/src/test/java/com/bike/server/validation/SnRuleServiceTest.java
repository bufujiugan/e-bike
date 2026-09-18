package com.bike.server.validation;

import com.bike.server.common.BusinessException;
import com.bike.server.system.SystemConfigMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnRuleServiceTest {
    @Test
    void defaultRuleTrimsAndNormalizesUppercase() {
        SnRuleService service = new SnRuleServiceImpl(null);

        assertThat(service.normalizeFrameSn(" frame-2026_001 ")).isEqualTo("FRAME-2026_001");
    }

    @Test
    void defaultRuleRejectsInvalidCharacters() {
        SnRuleService service = new SnRuleServiceImpl(null);

        assertThatThrownBy(() -> service.normalizePartSn("BAT 001", "BATTERY"))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("INVALID_SN_FORMAT");
    }

    @Test
    void configuredPartRuleSupportsTypeSpecificRegexAndPrefix() {
        SystemConfigMapper mapper = mock(SystemConfigMapper.class);
        when(mapper.selectValue("sn.part.min-length")).thenReturn("6");
        when(mapper.selectValue("sn.part.max-length")).thenReturn("32");
        when(mapper.selectValue("sn.part.BATTERY.regex")).thenReturn("BAT-[0-9]{3}");
        when(mapper.selectValue("sn.part.BATTERY.prefix")).thenReturn("BAT-");
        SnRuleService service = new SnRuleServiceImpl(mapper);

        assertThat(service.normalizePartSn(" bat-123 ", "battery")).isEqualTo("BAT-123");
        assertThatThrownBy(() -> service.normalizePartSn("MOT-123", "BATTERY"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("格式");
    }
}
