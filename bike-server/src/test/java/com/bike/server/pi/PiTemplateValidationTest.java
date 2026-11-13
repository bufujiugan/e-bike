package com.bike.server.pi;

import com.bike.server.common.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PiTemplateValidationTest {
    private final PiTemplateService service = mock(PiTemplateService.class);
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new PiTemplateController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @CsvSource({
        "templateCode,你写1,模板编码",
        "templateVersion,版本一,版本号",
        "sellerEmail,invalid@@example.com,卖方Email",
        "sellerAddress,'',卖方地址",
        "bankAccount,'',收款账号",
        "reason,'',发布原因"
    })
    void invalidTemplateNamesTheFieldBeforeEnteringBusinessService(String field, String value, String label) throws Exception {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("templateCode", "EXPORT_01");
        request.put("templateVersion", "V1.0");
        request.put("tradeType", "EXPORT");
        request.put("layoutCode", "STANDARD_V3");
        for (String key : new String[]{"sellerName", "sellerAddress", "bankName", "bankAccount", "beneficiary", "reason", "sellerCountry", "sellerContactName", "sellerPhone"}) {
            request.put(key, "测试资料");
        }
        request.put("sellerEmail", "sales@example.com");
        request.put(field, value);
        mvc.perform(post("/api/pi-templates").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value(containsString(label)));
        verifyNoInteractions(service);
    }
}
