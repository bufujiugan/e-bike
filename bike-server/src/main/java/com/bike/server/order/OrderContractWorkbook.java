package com.bike.server.order;

import com.bike.server.common.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class OrderContractWorkbook {
    private static final Logger LOG = LoggerFactory.getLogger(OrderContractWorkbook.class);
    private static final String TEMPLATE = "templates/domestic-purchase-contract.xlsx";
    private static final List<String> CLAUSE_CELLS = List.of("A10", "A23", "A24", "A26", "A27", "A28",
            "A29", "A30", "A36", "A37", "A38", "A39");

    public List<String> defaultClauses() {
        try (var input = new ClassPathResource(TEMPLATE).getInputStream(); var workbook = new XSSFWorkbook(input)) {
            return CLAUSE_CELLS.stream().map(address -> cell(workbook.getSheetAt(0), address).getStringCellValue()).toList();
        } catch (IOException exception) {
            LOG.error("读取内销合同模板失败", exception);
            throw new BusinessException("ORDER_CONTRACT_TEMPLATE_FAILED", "购销合同模板读取失败，请联系管理员");
        }
    }

    public byte[] render(SalesOrderView order, OrderContractRequest request) {
        try (var input = new ClassPathResource(TEMPLATE).getInputStream(); var workbook = new XSSFWorkbook(input);
                var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            int extraRows = Math.max(0, order.getSkuLines().size() - 9);
            if (extraRows > 0) {
                sheet.shiftRows(21, sheet.getLastRowNum(), extraRows);
                Row templateRow = sheet.getRow(20);
                for (int index = 21; index < 21 + extraRows; index++) {
                    Row row = sheet.createRow(index);
                    row.setHeight(templateRow.getHeight());
                    for (int column = 0; column <= 10; column++) {
                        row.createCell(column).setCellStyle(templateRow.getCell(column,
                                Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getCellStyle());
                    }
                }
            }
            text(sheet, "K1", request.signedDate().toString());
            text(sheet, "K2", request.contractNo());
            writeParty(sheet, "B", request.buyer());
            writeParty(sheet, "F", request.seller());
            text(sheet, "A11", "销售订单：" + order.getOrderNo());
            if (order.getSkuLines().isEmpty()) {
            number(sheet, "A13", BigDecimal.ONE);
            text(sheet, "B13", request.customsModel());
            text(sheet, "C13", request.productName());
            text(sheet, "D13", order.getModelName());
            text(sheet, "E13", request.appearance());
            number(sheet, "F13", BigDecimal.valueOf(order.getOrderQty()));
            text(sheet, "G13", "辆");
            number(sheet, "H13", request.usdUnitPrice());
            number(sheet, "I13", request.costUnitPrice());
            number(sheet, "J13", request.unitPrice());
            } else {
                var prices = request.skuPrices().stream().collect(java.util.stream.Collectors.toMap(
                        OrderContractRequest.SkuPrice::skuId, OrderContractRequest.SkuPrice::unitPrice));
                for (int index = 0; index < order.getSkuLines().size(); index++) {
                    var line = order.getSkuLines().get(index);
                    int row = 13 + index;
                    number(sheet, "A" + row, BigDecimal.valueOf(index + 1L));
                    text(sheet, "B" + row, request.customsModel());
                    text(sheet, "C" + row, line.productName() == null || line.productName().isBlank()
                            ? request.productName() : line.productName());
                    text(sheet, "D" + row, line.skuCode() + " / " + line.modelName());
                    text(sheet, "E" + row, line.specification());
                    number(sheet, "F" + row, BigDecimal.valueOf(line.orderedQty()));
                    text(sheet, "G" + row, "辆");
                    number(sheet, "J" + row, prices.get(line.skuId()));
                }
            }
            sheet.setColumnHidden(7, !order.getSkuLines().isEmpty() || request.usdUnitPrice() == null);
            sheet.setColumnHidden(8, !order.getSkuLines().isEmpty() || request.costUnitPrice() == null);
            // 修正原模板错行引用与 #REF!，金额按人民币逐行四舍五入到分。
            for (int row = 13; row <= 21 + extraRows; row++) {
                cell(sheet, "K" + row).setCellFormula("IF(F" + row + "=\"\",\"\",ROUND(F" + row + "*J" + row + ",2))");
            }
            cell(sheet, "F" + (22 + extraRows)).setCellFormula("SUM(F13:F" + (21 + extraRows) + ")");
            cell(sheet, "K" + (22 + extraRows)).setCellFormula("SUM(K13:K" + (21 + extraRows) + ")");
            for (int index = 0; index < CLAUSE_CELLS.size(); index++) {
                text(sheet, shifted(CLAUSE_CELLS.get(index), extraRows), request.clauses().get(index));
            }
            text(sheet, shifted("A25", extraRows), "二、签订时间：" + request.signedDate());
            text(sheet, shifted("C31", extraRows), request.paymentTerms());
            text(sheet, shifted("C32", extraRows), request.bankName());
            text(sheet, shifted("C33", extraRows), request.bankAccount());
            text(sheet, shifted("C34", extraRows), request.seller().name());
            text(sheet, shifted("C35", extraRows), request.seller().address());
            text(sheet, shifted("B42", extraRows), request.signedDate().toString());
            text(sheet, shifted("G42", extraRows), request.signedDate().toString());
            formatAmounts(workbook, sheet, extraRows);
            fitTextRows(workbook, sheet);
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.setPrintArea(0, "$A$1:$K$" + (42 + extraRows));
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException | RuntimeException exception) {
            LOG.error("生成内销购销合同失败 orderId={}", order.getId(), exception);
            throw new BusinessException("ORDER_CONTRACT_EXPORT_FAILED", "购销合同生成失败，请重试或联系管理员");
        }
    }

    private void writeParty(Sheet sheet, String column, OrderContractRequest.Party party) {
        text(sheet, column + "4", party.name());
        text(sheet, column + "5", party.address());
        text(sheet, column + "6", party.contact());
        text(sheet, column + "7", party.phone());
        text(sheet, column + "8", party.fax());
        text(sheet, column + "9", party.email());
    }

    private static String shifted(String address, int extraRows) {
        var cell = new CellAddress(address);
        return new CellAddress(cell.getRow() + (cell.getRow() >= 21 ? extraRows : 0),
                cell.getColumn()).formatAsString();
    }

    private void formatAmounts(XSSFWorkbook workbook, Sheet sheet, int extraRows) {
        short format = workbook.createDataFormat().getFormat("#,##0.00");
        for (int row = 12; row <= 21 + extraRows; row++) {
            for (int column = 7; column <= 10; column++) {
                Cell target = sheet.getRow(row).getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                CellStyle style = workbook.createCellStyle();
                style.cloneStyleFrom(target.getCellStyle());
                style.setDataFormat(format);
                target.setCellStyle(style);
            }
        }
    }

    private void fitTextRows(XSSFWorkbook workbook, Sheet sheet) {
        List<CellRangeAddress> merged = new ArrayList<>(sheet.getMergedRegions());
        for (Row row : sheet) {
            float height = row.getHeightInPoints();
            for (Cell target : row) {
                if (target.getCellType() != CellType.STRING || target.getStringCellValue().isBlank()) continue;
                CellRangeAddress region = merged.stream().filter(range -> range.isInRange(target)).findFirst().orElse(null);
                if (region != null && region.getFirstRow() != region.getLastRow()) continue;
                int start = region == null ? target.getColumnIndex() : region.getFirstColumn();
                int end = region == null ? start : region.getLastColumn();
                double width = 0;
                for (int column = start; column <= end; column++) width += sheet.getColumnWidth(column) / 256.0;
                var font = workbook.getFontAt(target.getCellStyle().getFontIndex());
                double capacity = Math.max(1, (width - 2) * 11 / font.getFontHeightInPoints());
                int lines = 0;
                for (String line : target.getStringCellValue().split("\n", -1)) {
                    int units = line.codePoints().map(point -> point > 255 ? 2 : 1).sum();
                    lines += Math.max(1, (int) Math.ceil(units / capacity));
                }
                height = Math.max(height, lines * font.getFontHeightInPoints() * 1.4f + 4);
                CellStyle style = workbook.createCellStyle();
                style.cloneStyleFrom(target.getCellStyle());
                style.setWrapText(true);
                target.setCellStyle(style);
            }
            row.setHeightInPoints(height);
        }
    }

    private static Cell cell(Sheet sheet, String address) {
        CellAddress reference = new CellAddress(address);
        return sheet.getRow(reference.getRow()).getCell(reference.getColumn(), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
    }

    private static void text(Sheet sheet, String address, String value) {
        // 按字符串写入，避免用户输入被 Excel 当成公式执行。
        Cell target = cell(sheet, address);
        // 先清除原 inlineStr 内容，避免部分 Excel 引擎遇到混合字符串编码后丢失条款。
        target.setBlank();
        target.setCellValue(value == null ? "" : value);
    }

    private static void number(Sheet sheet, String address, BigDecimal value) {
        if (value == null) cell(sheet, address).setBlank();
        else cell(sheet, address).setCellValue(value.doubleValue());
    }
}
