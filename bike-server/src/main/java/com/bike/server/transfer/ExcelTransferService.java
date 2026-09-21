package com.bike.server.transfer;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.master.MasterCreateRequest;
import com.bike.server.master.MasterService;
import com.bike.server.module.ModulePageResponse;
import com.bike.server.module.ModuleService;
import com.bike.server.order.OrderService;
import com.bike.server.order.SalesOrderCreateRequest;
import com.bike.server.part.PartCreateRequest;
import com.bike.server.part.PartService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ExcelTransferService {
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024;
    private final PartService partService;
    private final MasterService masterService;
    private final OrderService orderService;
    private final ModuleService moduleService;
    private final TransferAuditMapper auditMapper;
    private final jakarta.validation.Validator validator;

    public ExcelTransferService(PartService partService, MasterService masterService, OrderService orderService,
                                ModuleService moduleService, TransferAuditMapper auditMapper, jakarta.validation.Validator validator) {
        this.partService = partService;
        this.masterService = masterService;
        this.orderService = orderService;
        this.moduleService = moduleService;
        this.auditMapper = auditMapper;
        this.validator = validator;
    }

    public ImportResult importParts(MultipartFile file) { CurrentOperator.requirePermission("DATA_IMPORT"); CurrentOperator.requirePermission("PART_WRITE"); return importWorkbook(file, (values, row) -> partService.create(validated(new PartCreateRequest(required(values, 0), required(values, 1), longOrNull(values, 2), text(values, 3), blankToNull(text(values, 4)), blankToNull(text(values, 5)) )))); }

    public byte[] partsTemplate() {
        CurrentOperator.requirePermission("DATA_IMPORT");
        CurrentOperator.requirePermission("PART_WRITE");
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("零部件导入");
            String[] headers = {"零部件SN（必填）", "类型（MOTOR/BATTERY/DISPLAY）", "供应商ID（可空）", "批次（可空）", "质检状态（PASSED/FAILED）", "备注（可空）"};
            Row row = sheet.createRow(0);
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int i=0;i<headers.length;i++) { row.createCell(i).setCellValue(headers[i]); sheet.setColumnWidth(i, 28*256); sheet.setDefaultColumnStyle(i, textStyle); }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException error) { throw new BusinessException("EXPORT_FAILED", "模板生成失败"); }
    }

    public ImportResult importMaster(String type, MultipartFile file) {
        CurrentOperator.requirePermission("DATA_IMPORT");
        CurrentOperator.requireRole("SUPER_ADMIN");
        if (!List.of("customers", "agents").contains(type)) throw new BusinessException("IMPORT_TYPE_UNSUPPORTED", "仅支持客户和代理商导入");
        return importWorkbook(file, (values, row) -> masterService.create(type, validated(new MasterCreateRequest(required(values, 0), required(values, 1), blankToNull(text(values, 2)), blankToNull(text(values, 3))))));
    }

    public ImportResult importOrders(MultipartFile file) {
        CurrentOperator.requirePermission("DATA_IMPORT");
        CurrentOperator.requirePermission("ORDER_WRITE");
        return importWorkbook(file, (values, row) -> orderService.create(validated(new SalesOrderCreateRequest(
                requiredLong(values, 0), longOrNull(values, 1), requiredLong(values, 2), requiredInt(values, 3), dateOrNull(values, 4), blankToNull(text(values, 5))))));
    }

    private <T> T validated(T request) {
        var errors = validator.validate(request);
        if (!errors.isEmpty()) throw new BusinessException("IMPORT_VALUE_INVALID", errors.stream()
                .map(jakarta.validation.ConstraintViolation::getMessage).sorted().collect(java.util.stream.Collectors.joining("；")));
        return request;
    }

    public byte[] export(String module) { return export(module, null); }

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public byte[] export(String module, String keyword) {
        CurrentOperator.requirePermission("DATA_EXPORT");
        ModulePageResponse page = moduleService.list(module, keyword, 1, 100);
        if (page.total() > 1_048_575) throw new BusinessException("EXPORT_LIMIT", "结果超出单个Excel工作表容量，请缩小查询范围");
        try (var workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet(module);
            Row header = sheet.createRow(0);
            int[] widths = new int[page.columns().size()];
            for (int i = 0; i < page.columns().size(); i++) {
                header.createCell(i).setCellValue(page.columns().get(i));
                widths[i] = Math.min(80, Math.max(16, page.columns().get(i).length() * 2 + 2));
            }
            long expected = page.total(), pageNumber = 1;
            int written = 0;
            while (true) {
                for (List<String> values : page.rows()) {
                    Row row = sheet.createRow(++written);
                    for (int c = 0; c < values.size(); c++) {
                        row.createCell(c).setCellValue(values.get(c));
                        widths[c] = Math.min(80, Math.max(widths[c], values.get(c).length() * 2 + 2));
                    }
                }
                if (written >= expected) break;
                page = moduleService.list(module, keyword, ++pageNumber, 100);
                if (page.rows().isEmpty()) throw new BusinessException("EXPORT_INCOMPLETE", "导出数据不完整，请重新查询后重试");
            }
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            workbook.write(output);
            auditMapper.insert("DATA_EXPORT", module, CurrentOperator.id(), CurrentOperator.username(), CurrentOperator.clientIp(), null, "{\"module\":\"" + module + "\"}");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException("EXPORT_FAILED", "Excel 导出失败");
        }
    }

    private ImportResult importWorkbook(MultipartFile file, RowImporter importer) {
        validateFile(file);
        List<ImportResult.ImportFailure> failures = new ArrayList<>();
        int total = 0;
        int success = 0;
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlank(row)) continue;
                total++;
                try {
                    importer.importRow(values(row), i + 1);
                    success++;
                } catch (RuntimeException exception) {
                    failures.add(new ImportResult.ImportFailure(i + 1, readableReason(exception)));
                }
            }
        } catch (Exception exception) {
            throw new BusinessException("IMPORT_FORMAT_INVALID", "Excel 文件格式无法读取");
        }
        auditMapper.insert("DATA_IMPORT", String.valueOf(total), CurrentOperator.id(), CurrentOperator.username(), CurrentOperator.clientIp(), null, "{\"total\":" + total + ",\"success\":" + success + ",\"failed\":" + failures.size() + "}");
        return new ImportResult(total, success, failures.size(), failures);
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("IMPORT_FILE_REQUIRED", "请上传 Excel 文件");
        if (file.getSize() > MAX_FILE_BYTES) throw new BusinessException("IMPORT_FILE_TOO_LARGE", "Excel 文件不能超过 10MB");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".xlsx") || name.endsWith(".xls"))) throw new BusinessException("IMPORT_FILE_TYPE_INVALID", "仅支持 xls 或 xlsx 文件");
    }

    private static List<String> values(Row row) { List<String> result = new ArrayList<>(); DataFormatter formatter = new DataFormatter(); for (int i = 0; i < Math.max(6, row.getLastCellNum()); i++) result.add(formatter.formatCellValue(row.getCell(i)).trim()); return result; }
    private static boolean isBlank(Row row) { for (Cell cell : row) if (cell != null && cell.getCellType() != CellType.BLANK && !cell.toString().isBlank()) return false; return true; }
    private static String text(List<String> values, int index) { return index < values.size() ? values.get(index) : ""; }
    private static String required(List<String> values, int index) { String value = text(values, index); if (value.isBlank()) throw new BusinessException("IMPORT_REQUIRED", "第 " + (index + 1) + " 列不能为空"); return value; }
    private static long requiredLong(List<String> values, int index) { try { return Long.parseLong(required(values, index)); } catch (NumberFormatException exception) { throw new BusinessException("IMPORT_NUMBER_INVALID", "数字格式不正确"); } }
    private static Long longOrNull(List<String> values, int index) { String value = text(values, index); return value.isBlank() ? null : requiredLong(values, index); }
    private static int requiredInt(List<String> values, int index) { try { return Integer.parseInt(required(values, index)); } catch (NumberFormatException exception) { throw new BusinessException("IMPORT_NUMBER_INVALID", "数量格式不正确"); } }
    private static LocalDate dateOrNull(List<String> values, int index) { String value = text(values, index); if (value.isBlank()) return null; try { return LocalDate.parse(value); } catch (DateTimeParseException exception) { throw new BusinessException("IMPORT_DATE_INVALID", "日期格式应为 yyyy-MM-dd"); } }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static String readableReason(RuntimeException exception) { return exception instanceof BusinessException ? exception.getMessage() : "数据校验失败"; }

    @FunctionalInterface private interface RowImporter { void importRow(List<String> values, int rowNumber); }
}
