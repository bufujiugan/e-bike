package com.bike.server.order;

import com.bike.server.common.BusinessException;
import com.bike.server.common.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderContractServiceTest {
    private final OrderService orders = mock(OrderService.class);
    private final OrderContractMapper mapper = mock(OrderContractMapper.class);
    private final OrderContractWorkbook workbook = new OrderContractWorkbook();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final OrderContractServiceImpl service = new OrderContractServiceImpl(orders, mapper, workbook, validation.getValidator());
    private SalesOrderView order;

    @BeforeEach void setup() {
        authenticate("ROLE_SUPER_ADMIN");
        order = new SalesOrderView();
        order.setId(81L); order.setOrderNo("SO-CONTRACT-TEST"); order.setCustomerId(12L);
        order.setCustomerName("测试需方有限公司"); order.setModelId(20L); order.setModelName("测试车型 M");
        order.setOrderQty(3); order.setStatus("CONFIRMED"); order.setUpdatedAt(LocalDateTime.of(2026, 11, 19, 10, 0));
        when(orders.get(81L)).thenReturn(order);
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); validation.close(); }

    private void authenticate(String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("contract-test", null,
                java.util.Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }

    private ObjectNode validPayload() {
        ObjectNode payload = json.valueToTree(service.prepareContract(81).contract());
        payload.put("contractNo", "TEST-20261119"); payload.put("signedDate", "2026-11-19");
        payload.put("unitPrice", "1234.56"); payload.put("bankAccount", "001234567890");
        ((ObjectNode) payload.get("seller")).put("name", "测试供方有限公司").put("address", "测试地址，仅供验证");
        return payload;
    }

    private byte[] generate(ObjectNode payload) throws Exception {
        return service.generateContract(81, json.treeToValue(payload, OrderContractRequest.class));
    }

    @Test void exportsTemplateWithCorrectTotalsAndNoHistoricalData() throws Exception {
        byte[] bytes = generate(validPayload());
        try (var excel = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = excel.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("购销合同");
            assertThat(sheet.getNumMergedRegions()).isEqualTo(44);
            assertThat(excel.getPrintArea(0)).contains("$A$1:$K$42");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo(order.getCustomerName());
            assertThat(sheet.getRow(12).getCell(3).getStringCellValue()).isEqualTo(order.getModelName());
            assertThat(sheet.getRow(12).getCell(5).getNumericCellValue()).isEqualTo(3);
            assertThat(sheet.getRow(12).getCell(10).getNumericCellValue()).isEqualTo(3703.68);
            assertThat(sheet.getRow(21).getCell(10).getNumericCellValue()).isEqualTo(3703.68);
            assertThat(sheet.getRow(32).getCell(2).getStringCellValue()).isEqualTo("001234567890");
            assertThat(sheet.getRow(35).getCell(0).getStringCellValue()).startsWith("九、违约责任");
            assertThat(sheet.isColumnHidden(7)).isTrue();
            assertThat(sheet.isColumnHidden(8)).isTrue();
            for (int row = 13; row <= 20; row++) {
                assertThat(sheet.getRow(row).getCell(1).getCellType()).isEqualTo(CellType.BLANK);
            }
            for (var row : sheet) for (var cell : row) {
                assertThat(cell.getCellType()).isNotEqualTo(CellType.ERROR);
                if (cell.getCellType() == CellType.FORMULA) {
                    assertThat(cell.getCellFormula()).doesNotContain("#REF!");
                    assertThat(cell.getCachedFormulaResultType()).isNotEqualTo(CellType.ERROR);
                }
            }
        }
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith(".xml")) assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .doesNotContain("广州塔威", "金轮信德", "TW2026102301", "Flux One", "2920", "wxid_");
            }
        }
        // 仅导出虚构数据，供独立的 Excel 渲染与版式验证使用。
        Path preview = Path.of("target", "contract-preview.xlsx");
        Files.write(preview, bytes);
        Files.write(Path.of("target", "contract-fixture.json"), json.writeValueAsBytes(validPayload()));
    }

    @Test void treatsFormulaLikeInputsAsText() throws Exception {
        var payload = validPayload(); payload.put("bankName", "=HYPERLINK(\"https://example.invalid\")");
        try (var excel = new XSSFWorkbook(new ByteArrayInputStream(generate(payload)))) {
            assertThat(excel.getSheetAt(0).getRow(31).getCell(2).getCellType()).isEqualTo(CellType.STRING);
        }
    }

    @Test void exposesOptionalPricesOnlyWhenFilled() throws Exception {
        var payload = validPayload(); payload.put("usdUnitPrice", "170.50"); payload.put("costUnitPrice", "1000.00");
        try (var excel = new XSSFWorkbook(new ByteArrayInputStream(generate(payload)))) {
            var sheet = excel.getSheetAt(0);
            assertThat(sheet.isColumnHidden(7)).isFalse();
            assertThat(sheet.isColumnHidden(8)).isFalse();
            assertThat(sheet.getRow(12).getCell(7).getNumericCellValue()).isEqualTo(170.5);
            assertThat(sheet.getRow(21).getCell(10).getNumericCellValue()).isEqualTo(3703.68);
        }
    }

    @Test void rejectsLinkedPiEvenWhenDirectlyCallingExport() {
        var payload = validPayload(); when(mapper.existsPiForOrder(81L)).thenReturn(true);
        assertThat(service.prepareContract(81).available()).isFalse();
        assertThatThrownBy(() -> generate(payload)).isInstanceOf(BusinessException.class).hasMessageContaining("PI");
    }

    @Test void rejectsCancelledAndClosedOrders() {
        var payload = validPayload();
        for (String status : List.of("CANCELLED", "CLOSED")) {
            order.setStatus(status);
            assertThat(service.prepareContract(81).available()).isFalse();
            assertThatThrownBy(() -> generate(payload)).isInstanceOf(BusinessException.class).hasMessageContaining("作废");
        }
    }

    @Test void rejectsStaleQuantityEvenIfTimestampDidNotChange() {
        var payload = validPayload(); order.setOrderQty(5);
        assertThatThrownBy(() -> generate(payload)).isInstanceOf(BusinessException.class).hasMessageContaining("发生变化");
    }

    @Test void rejectsStaleTimestampAndBuyerSubstitution() {
        var payload = validPayload(); payload.put("orderUpdatedAt", "2026-11-18T10:00:00");
        assertThatThrownBy(() -> generate(payload)).isInstanceOf(BusinessException.class).hasMessageContaining("发生变化");
        var substituted = validPayload(); ((ObjectNode) substituted.get("buyer")).put("name", "其他客户");
        assertThatThrownBy(() -> generate(substituted)).isInstanceOf(BusinessException.class).hasMessageContaining("需方");
    }

    @Test void rejectsInvalidMoneyAndIncompleteClauses() {
        for (String price : List.of("0", "-1", "1.999", "100000000")) {
            var payload = validPayload(); payload.put("unitPrice", price);
            assertThatThrownBy(() -> generate(payload)).isInstanceOf(BusinessException.class).hasMessageContaining("必填项");
        }
        var payload = validPayload(); payload.putArray("clauses").add("仅一条");
        assertThatThrownBy(() -> generate(payload)).isInstanceOf(BusinessException.class).hasMessageContaining("必填项");
    }

    @Test void rejectsAmountOutsideExcelPrecision() {
        order.setOrderQty(1000000000);
        var payload = validPayload(); payload.put("unitPrice", "99999999.99");
        assertThatThrownBy(() -> generate(payload)).isInstanceOf(BusinessException.class).hasMessageContaining("金额超过");
    }

    @Test void mapperChecksBothPiOrderRelations() throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        try (var input = getClass().getResourceAsStream("/mapper/OrderContractMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration, "mapper/OrderContractMapper.xml", configuration.getSqlFragments()).parse();
        }
        String sql = configuration.getMappedStatement(OrderContractMapper.class.getName() + ".existsPiForOrder")
                .getBoundSql(java.util.Map.of("orderId", 81L)).getSql();
        assertThat(sql).contains("bike_pi WHERE source_order_id=?", "bike_pi_order_link WHERE order_id=?");
    }

    @Test void requiresInternalReadAndWritePermissions() {
        for (String authority : List.of("ROLE_WEB_AGENT", "ROLE_WEB_CUSTOMER", "ROLE_PDA_OPERATOR", "PERM_ORDER_READ", "PERM_ORDER_WRITE")) {
            authenticate(authority);
            assertThatThrownBy(() -> service.prepareContract(81)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(orders);
    }

    @Test void preservesOrderCustomerScopeEnforcement() {
        when(orders.get(81L)).thenThrow(new BusinessException("FORBIDDEN", "无权访问该客户"));
        assertThatThrownBy(() -> service.prepareContract(81)).hasMessage("无权访问该客户");
        verifyNoInteractions(mapper);
    }

    @Test void servesExcelAndValidatesNestedHttpPayload() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new OrderContractController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var payload = validPayload();
        mvc.perform(post("/api/orders/81/purchase-contract/export.xlsx").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        ((ObjectNode) payload.get("seller")).put("name", "");
        mvc.perform(post("/api/orders/81/purchase-contract/export.xlsx").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
                .andExpect(status().isBadRequest());
    }
}
