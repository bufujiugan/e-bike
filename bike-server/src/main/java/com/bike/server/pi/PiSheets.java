package com.bike.server.pi;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 参考版式渲染的单元格工具：用户输入一律按文本写入，不作为公式执行。 */
final class PiSheets {
    private PiSheets() {}

    static Cell cell(Sheet sheet, String address) {
        var reference = new CellReference(address);
        return cell(sheet, reference.getRow(), reference.getCol());
    }

    static Cell cell(Sheet sheet, int row, int col) {
        Row target = sheet.getRow(row);
        if (target == null) target = sheet.createRow(row);
        Cell cell = target.getCell(col);
        return cell == null ? target.createCell(col) : cell;
    }

    static void text(Sheet sheet, String address, String value) { text(cell(sheet, address), value); }

    static void text(Cell cell, String value) {
        cell.setBlank();
        cell.setCellValue(Objects.toString(value, ""));
    }

    /** 粗体标签 + 普通内容的富文本，与参考文件“Account Name: xxx”写法一致。 */
    static void labeled(XSSFWorkbook book, Cell cell, String label, String value) {
        XSSFFont base = book.getFontAt(cell.getCellStyle().getFontIndex());
        XSSFFont bold = book.createFont();
        bold.setFontName(base.getFontName()); bold.setFontHeightInPoints(base.getFontHeightInPoints()); bold.setBold(true);
        bold.setCharSet(base.getCharSet());
        XSSFFont plain = book.createFont();
        plain.setFontName(base.getFontName()); plain.setFontHeightInPoints(base.getFontHeightInPoints()); plain.setBold(false);
        plain.setCharSet(base.getCharSet());
        String body = Objects.toString(value, "");
        var rich = new XSSFRichTextString(label + body);
        rich.applyFont(0, label.length(), bold);
        if (!body.isEmpty()) rich.applyFont(label.length(), label.length() + body.length(), plain);
        cell.setBlank();
        cell.setCellValue(rich);
    }

    static void number(Cell cell, BigDecimal value) {
        if (value == null) { cell.setBlank(); return; }
        // 超过 Excel 双精度可靠范围时保留精确文本，正式金额不因格式转换改变。
        if (value.stripTrailingZeros().precision() > 15) cell.setCellValue(value.toPlainString());
        else cell.setCellValue(value.doubleValue());
    }

    static void merge(Sheet sheet, int row, int first, int last) {
        if (first < last) sheet.addMergedRegion(new CellRangeAddress(row, row, first, last));
    }

    static void removeRowsFrom(Sheet sheet, int firstRow) {
        for (int index = sheet.getNumMergedRegions() - 1; index >= 0; index--)
            if (sheet.getMergedRegion(index).getLastRow() >= firstRow) sheet.removeMergedRegion(index);
        for (int index = sheet.getLastRowNum(); index >= firstRow; index--) {
            Row row = sheet.getRow(index);
            if (row != null) sheet.removeRow(row);
        }
    }

    static CellStyle[] styles(Sheet sheet, int row, int columns) {
        CellStyle[] result = new CellStyle[columns];
        for (int col = 0; col < columns; col++) result[col] = cell(sheet, row, col).getCellStyle();
        return result;
    }

    static CellStyle derived(Workbook book, CellStyle source, java.util.function.Consumer<CellStyle> change) {
        CellStyle style = book.createCellStyle();
        style.cloneStyleFrom(source);
        change.accept(style);
        return style;
    }

    static String join(String separator, String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank()).map(String::strip).collect(Collectors.joining(separator));
    }

    static String text(String value) { return Objects.toString(value, ""); }

    static BigDecimal amount(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    static boolean blank(String value) { return value == null || value.isBlank(); }

    /** 查找指定列文字所在行（0 起始），找不到返回 fallback。 */
    static int findRow(Sheet sheet, int col, String value, int fallback) {
        for (Row row : sheet) {
            Cell cell = row.getCell(col);
            if (cell != null && cell.getCellType() == CellType.STRING && value.equals(cell.getStringCellValue().strip())) return row.getRowNum();
        }
        return fallback;
    }

    static int printLastRow(XSSFWorkbook book) {
        String area = book.getPrintArea(0);
        if (area == null) return book.getSheetAt(0).getLastRowNum();
        return new org.apache.poi.ss.util.AreaReference(area.substring(area.indexOf('!') + 1), book.getSpreadsheetVersion()).getLastCell().getRow();
    }
}
