package com.bike.server.transfer;

import com.bike.server.common.BusinessException;
import com.bike.server.master.MasterService;
import com.bike.server.module.ModulePageResponse;
import com.bike.server.module.ModuleService;
import com.bike.server.order.OrderService;
import com.bike.server.part.PartService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelTransferServiceTest {
    static final jakarta.validation.ValidatorFactory validationFactory = jakarta.validation.Validation.buildDefaultValidatorFactory();
    static final jakarta.validation.Validator validator = validationFactory.getValidator();
    @org.junit.jupiter.api.AfterAll static void closeValidator() { validationFactory.close(); }

    @org.junit.jupiter.api.BeforeEach
    void setAuthenticatedAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-admin", "N/A", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void importReturnsPerRowFailureInsteadOfAbortingWholeWorkbook() throws Exception {
        PartService partService = Mockito.mock(PartService.class);
        MasterService masterService = Mockito.mock(MasterService.class);
        OrderService orderService = Mockito.mock(OrderService.class);
        ModuleService moduleService = Mockito.mock(ModuleService.class);
        TransferAuditMapper auditMapper = Mockito.mock(TransferAuditMapper.class);
        Mockito.when(partService.create(Mockito.argThat(request -> request != null && "PART-OK".equals(request.partSn())))).thenReturn(null);
        Mockito.when(partService.create(Mockito.argThat(request -> request != null && "PART-BAD".equals(request.partSn()))))
                .thenThrow(new BusinessException("PART_ALREADY_EXISTS", "零部件 SN 已存在"));

        MockMultipartFile file = workbookFile("parts.xlsx", workbook -> {
            Row first = workbook.getSheetAt(0).createRow(1);
            first.createCell(0).setCellValue("PART-OK");
            first.createCell(1).setCellValue("MOTOR");
            Row second = workbook.getSheetAt(0).createRow(2);
            second.createCell(0).setCellValue("PART-BAD");
            second.createCell(1).setCellValue("MOTOR");
        });

        ImportResult result = new ExcelTransferService(partService, masterService, orderService, moduleService, auditMapper, validator)
                .importParts(file);

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.successRows()).isEqualTo(1);
        assertThat(result.failureRows()).isEqualTo(1);
        assertThat(result.failures().get(0).rowNumber()).isEqualTo(3);
        assertThat(result.failures().get(0).reason()).contains("SN 已存在");
        Mockito.verify(auditMapper).insert("DATA_IMPORT", "2", null, "test-admin", null, null, "{\"total\":2,\"success\":1,\"failed\":1}");
    }

    @Test
    void importRejectsUnsupportedExtensionBeforeOpeningFile() {
        PartService partService = Mockito.mock(PartService.class);
        MockMultipartFile file = new MockMultipartFile("file", "parts.txt", "text/plain", "not excel".getBytes());

        assertThatThrownBy(() -> new ExcelTransferService(partService, Mockito.mock(MasterService.class), Mockito.mock(OrderService.class), Mockito.mock(ModuleService.class), Mockito.mock(TransferAuditMapper.class), validator).importParts(file))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("IMPORT_FILE_TYPE_INVALID");
        Mockito.verifyNoInteractions(partService);
    }

    @Test
    void importRejectsMalformedWorkbookWithSupportedExtension() {
        PartService partService = Mockito.mock(PartService.class);
        MockMultipartFile file = new MockMultipartFile("file", "parts.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "not-an-xlsx".getBytes());

        assertThatThrownBy(() -> new ExcelTransferService(partService, Mockito.mock(MasterService.class),
                Mockito.mock(OrderService.class), Mockito.mock(ModuleService.class), Mockito.mock(TransferAuditMapper.class), validator)
                .importParts(file))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("IMPORT_FORMAT_INVALID");
        Mockito.verifyNoInteractions(partService);
    }

    @Test
    void exportProducesXlsxHeaderAndRows() {
        ModulePageResponse page = new ModulePageResponse("说明", List.of(), List.of("列1", "列2"), List.of(List.of("值1", "值2")), "导出");
        ModuleService moduleService = Mockito.mock(ModuleService.class);
        Mockito.when(moduleService.list("vehicles", null, 1, 100)).thenReturn(page);
        TransferAuditMapper auditMapper = Mockito.mock(TransferAuditMapper.class);

        byte[] content = new ExcelTransferService(Mockito.mock(PartService.class), Mockito.mock(MasterService.class), Mockito.mock(OrderService.class), moduleService, auditMapper, validator).export("vehicles");

        assertThat(content).isNotEmpty();
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(content))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("列1");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("值2");
        } catch (Exception exception) {
            throw new AssertionError("导出内容不是有效 xlsx", exception);
        }
        Mockito.verify(auditMapper).insert("DATA_EXPORT", "vehicles", null, "test-admin", null, null, "{\"module\":\"vehicles\"}");
    }

    @Test void zeroQuantityImportIsRejectedBeforeOrderWrite() throws Exception {
        OrderService orders = Mockito.mock(OrderService.class);
        var file = workbookFile("orders.xlsx", workbook -> {
            var row = workbook.getSheetAt(0).createRow(1);
            row.createCell(0).setCellValue("1"); row.createCell(2).setCellValue("1"); row.createCell(3).setCellValue("0");
        });
        var result = new ExcelTransferService(Mockito.mock(PartService.class), Mockito.mock(MasterService.class), orders,
                Mockito.mock(ModuleService.class), Mockito.mock(TransferAuditMapper.class), validator).importOrders(file);
        assertThat(result.successRows()).isZero();
        assertThat(result.failures().getFirst().reason()).contains("订单数量必须大于 0");
        Mockito.verifyNoInteractions(orders);
    }

    @Test void invalidQualityStatusIsRejectedBeforePartWrite() throws Exception {
        PartService parts = Mockito.mock(PartService.class);
        var file = workbookFile("parts.xlsx", workbook -> {
            var row = workbook.getSheetAt(0).createRow(1);
            row.createCell(0).setCellValue("SN-1"); row.createCell(1).setCellValue("MOTOR"); row.createCell(4).setCellValue("UNKNOWN");
        });
        var result = new ExcelTransferService(parts, Mockito.mock(MasterService.class), Mockito.mock(OrderService.class),
                Mockito.mock(ModuleService.class), Mockito.mock(TransferAuditMapper.class), validator).importParts(file);
        assertThat(result.successRows()).isZero();
        assertThat(result.failures().getFirst().reason()).contains("质检状态");
        Mockito.verifyNoInteractions(parts);
    }

    private static MockMultipartFile workbookFile(String name, WorkbookWriter writer) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Sheet1");
            writer.write(workbook);
            workbook.write(output);
            return new MockMultipartFile("file", name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    @FunctionalInterface
    private interface WorkbookWriter { void write(Workbook workbook); }
}
