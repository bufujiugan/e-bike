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

    public ExcelTransferService(PartService partService, MasterService masterService, OrderService orderService,
                                ModuleService moduleService, TransferAuditMapper auditMapper) {
        this.partService = partService;
        this.masterService = masterService;
        this.orderService = orderService;
        this.moduleService = moduleService;
        this.auditMapper = auditMapper;
    }

    public ImportResult importParts(MultipartFile file) { CurrentOperator.requirePermission("DATA_IMPORT"); return importWorkbook(file, (values, row) -> partService.create(new PartCreateRequest(required(values, 0), required(values, 1), longOrNull(values, 2), text(values, 3), blankToNull(text(values, 4)), blankToNull(text(values, 5)) ))); }

    public ImportResult importMaster(String type, MultipartFile file) {
        CurrentOperator.requirePermission("DATA_IMPORT");
        if (!List.of("customers", "agents").contains(type)) throw new BusinessException("IMPORT_TYPE_UNSUPPORTED", "仅支持客户和代理商导入");
        return importWorkbook(file, (values, row) -> masterService.create(type, new MasterCreateRequest(required(values, 0), required(values, 1), blankToNull(text(values, 2)), blankToNull(text(values, 3)))));
    }

    public ImportResult importOrders(MultipartFile file) {
        CurrentOperator.requirePermission("DATA_IMPORT");
        return importWorkbook(file, (values, row) -> orderService.create(new SalesOrderCreateRequest(
                requiredLong(values, 0), longOrNull(values, 1), requiredLong(values, 2), requiredInt(values, 3), dateOrNull(values, 4), blankToNull(text(values, 5)))));
    }

    public byte[] export(String module) {
        CurrentOperator.requirePermission("DATA_EXPORT");
        ModulePageResponse page = loadAllModuleRows(module);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(module);
            Row header = sheet.createRow(0);
            for (int i = 0; i < page.columns().size(); i++) header.createCell(i).setCellValue(page.columns().get(i));
            for (int r = 0; r < page.rows().size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = page.rows().get(r);
                for (int c = 0; c < values.size(); c++) row.createCell(c).setCellValue(values.get(c));
            }
            for (int i = 0; i < page.columns().size(); i++) sheet.autoSizeColumn(i);
            workbook.write(output);
            auditMapper.insert("DATA_EXPORT", module, CurrentOperator.id(), CurrentOperator.username(), CurrentOperator.clientIp(), null, "{\"module\":\"" + module + "\"}");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException("EXPORT_FAILED", "Excel 导出失败");
        }
    }

    private ModulePageResponse loadAllModuleRows(String module) {
        ModulePageResponse first = moduleService.list(module, null, 1, 100);
        if (first.total() <= first.rows().size()) return first;
        List<List<String>> rows = new ArrayList<>(first.rows());
        long page = 2;
        while (rows.size() < first.total()) {
            ModulePageResponse next = moduleService.list(module, null, page++, 100);
            if (next.rows().isEmpty()) break;
            rows.addAll(next.rows());
        }
        return new ModulePageResponse(first.subtitle(), first.stats(), first.columns(), rows, first.action(), 1, rows.size(), rows.size());
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
