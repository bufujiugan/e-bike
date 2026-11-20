package com.bike.server.pi;

import static com.bike.server.pi.PiSheets.*;

import com.bike.server.common.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.LoggerFactory;

/**
 * PI_DOMESTIC_CONTRACT_V1：没有销售入口标识、且为国内出货的历史内销 PI，按
 * 「10.23金轮信德-广州塔威 购销合同」版式输出（与销售订单 → 内销使用同一份脱敏模板）。
 * 只读取本单快照：供方为 PI 卖方，需方为 PI 客户；报关型号、外观等 PI 没有的合同字段留空。
 */
final class PiDomesticContractRenderer {
    static final String VERSION = "PI_DOMESTIC_CONTRACT_V1";
    private static final int FIRST_ITEM = 12, TEMPLATE_ITEMS = 9, LAST_COL = 10;

    byte[] excel(PiView pi) {
        try (var book = workbook(pi); var output = new ByteArrayOutputStream()) {
            book.write(output);
            return output.toByteArray();
        } catch (IOException | RuntimeException error) { throw failed(pi, error); }
    }

    byte[] pdf(PiView pi) {
        try (var book = workbook(pi)) {
            var sheet = book.getSheetAt(0);
            int lastRow = printLastRow(book);
            return new PiSheetPdfRenderer().render(book, sheet, new PiSheetPdfRenderer.Options(0, LAST_COL, lastRow,
                    11, 11, findRow(sheet, 0, "合计", lastRow), "购销合同 " + pi.getPiNo(), null, true));
        } catch (IOException | RuntimeException error) { throw failed(pi, error); }
    }

    private BusinessException failed(PiView pi, Exception error) {
        LoggerFactory.getLogger(getClass()).warn("Domestic contract rendering failed, piId={}", pi.getId(), error);
        return new BusinessException("PI_EXPORT_FAILED", "购销合同文件生成失败，请重试");
    }

    XSSFWorkbook workbook(PiView pi) throws IOException {
        try (var input = getClass().getResourceAsStream("/templates/domestic-purchase-contract.xlsx")) {
            if (input == null) throw new IOException("Domestic contract layout is missing");
            var book = new XSSFWorkbook(input);
            try { populate(book, pi); return book; }
            catch (IOException | RuntimeException error) { book.close(); throw error; }
        }
    }

    private void populate(XSSFWorkbook book, PiView pi) throws IOException {
        var sheet = book.getSheetAt(0);
        var d = pi.getDocument();
        String currency = text(pi.getCurrencyCode()).toUpperCase(Locale.ROOT);
        String unitLabel = "CNY".equals(currency) || "RMB".equals(currency) || currency.isEmpty() ? "RMB" : currency;
        var issued = d != null && d.issueDate() != null ? d.issueDate() : pi.getCreatedAt() == null ? null : pi.getCreatedAt().toLocalDate();
        String date = issued == null ? "" : issued.toString();

        // 先按调整行数扩展商品区，原合计及条款区整体下移，保留合并与样式。
        var adjustments = new ArrayList<Object[]>();
        addAdjustment(adjustments, "折扣", amount(pi.getDiscountAmount()).negate());
        addAdjustment(adjustments, "税费", amount(pi.getTaxAmount()));
        addAdjustment(adjustments, "运费", amount(pi.getFreightAmount()));
        addAdjustment(adjustments, "其他费用", amount(pi.getOtherFeeAmount()));
        int needed = pi.getItems().size() + adjustments.size();
        int extra = Math.max(0, needed - TEMPLATE_ITEMS);
        CellStyle[] itemStyles = styles(sheet, FIRST_ITEM, LAST_COL + 1);
        float itemHeight = sheet.getRow(FIRST_ITEM).getHeightInPoints();
        if (extra > 0) {
            sheet.shiftRows(FIRST_ITEM + TEMPLATE_ITEMS, sheet.getLastRowNum(), extra, true, false);
            for (int index = 0; index < extra; index++) {
                Row target = sheet.createRow(FIRST_ITEM + TEMPLATE_ITEMS + index);
                target.setHeightInPoints(itemHeight);
                for (int col = 0; col <= LAST_COL; col++) target.createCell(col).setCellStyle(itemStyles[col]);
            }
        }
        int itemRows = TEMPLATE_ITEMS + extra, totalRow = FIRST_ITEM + itemRows, shift = extra;

        text(sheet, "K1", date);
        text(sheet, "K2", text(pi.getPiNo()) + (pi.getRevision() > 1 ? " R" + pi.getRevision() : ""));
        text(sheet, "B4", pi.getCustomerName());
        text(sheet, "F4", d == null ? "" : d.sellerName());
        if (d != null) {
            text(sheet, "B5", join(" ", d.buyerAddress(), contains(d.buyerAddress(), d.buyerCountry()) ? null : d.buyerCountry()));
            text(sheet, "B6", d.buyerContactName());
            text(sheet, "B7", d.buyerPhone());
            text(sheet, "B9", d.buyerEmail());
            text(sheet, "F5", join(" ", d.sellerAddress(), contains(d.sellerAddress(), d.sellerCountry()) ? null : d.sellerCountry()));
            text(sheet, "F6", d.sellerContactName());
            text(sheet, "F7", d.sellerPhone());
            text(sheet, "F9", d.sellerEmail());
        }
        text(sheet, "A11", "PI：" + text(pi.getPiNo()) + (blank(pi.getSourceOrderNo()) ? "" : "　销售订单：" + pi.getSourceOrderNo()));
        text(sheet, "J12", "单价\n(" + unitLabel + "）");
        text(sheet, "K12", "总金额\n(" + unitLabel + "）");
        short money = book.createDataFormat().getFormat("#,##0.00");
        CellStyle moneyStyle = derived(book, itemStyles[9], style -> style.setDataFormat(money));
        CellStyle lineStyle = derived(book, itemStyles[10], style -> style.setDataFormat(money));
        int row = FIRST_ITEM, index = 1, quantity = 0;
        for (var item : pi.getItems()) {
            Row target = sheet.getRow(row);
            for (int col = 0; col <= LAST_COL; col++) { var c = cell(sheet, row, col); c.setBlank(); }
            target.getCell(0).setCellValue(index++);
            text(target.getCell(2), item.getProductName());
            text(target.getCell(3), join(" / ", item.getModelName(), item.getSpecification()));
            target.getCell(5).setCellValue(item.getQuantity());
            text(target.getCell(6), blank(item.getUnit()) ? "辆" : item.getUnit());
            target.getCell(9).setCellStyle(moneyStyle);
            target.getCell(10).setCellStyle(lineStyle);
            number(target.getCell(9), item.getUnitPrice());
            number(target.getCell(10), amount(item.getUnitPrice()).multiply(BigDecimal.valueOf(item.getQuantity())));
            quantity += item.getQuantity();
            row++;
        }
        for (Object[] adjustment : adjustments) {
            for (int col = 0; col <= LAST_COL; col++) cell(sheet, row, col).setBlank();
            text(cell(sheet, row, 3), (String) adjustment[0]);
            cell(sheet, row, 10).setCellStyle(lineStyle);
            number(cell(sheet, row, 10), (BigDecimal) adjustment[1]);
            row++;
        }
        for (; row < totalRow; row++) for (int col = 0; col <= LAST_COL; col++) cell(sheet, row, col).setBlank();
        // 不依赖打开 Excel 重算：合计直接写入保存的数量与成交总额。
        cell(sheet, totalRow, 5).setBlank();
        cell(sheet, totalRow, 5).setCellValue(quantity);
        cell(sheet, totalRow, 10).setBlank();
        number(cell(sheet, totalRow, 10), pi.getTotalAmount());
        sheet.setColumnHidden(7, true);
        sheet.setColumnHidden(8, true);

        String remark = text(cell(sheet, 22 + shift, 0).getStringCellValue());
        if (!blank(pi.getRemark())) text(cell(sheet, 22 + shift, 0), remark + "　" + pi.getRemark().strip());
        text(cell(sheet, 24 + shift, 0), "二、签订时间：" + date);
        if (d != null && !blank(d.shippingAddress())) text(cell(sheet, 25 + shift, 0), "三、交货地点：" + d.shippingAddress().strip());
        if (d != null && !blank(d.deliveryTerms())) text(cell(sheet, 26 + shift, 0), "四、交货方式：" + d.deliveryTerms().strip());
        if (d != null) {
            if (!blank(d.paymentTerms())) text(cell(sheet, 30 + shift, 2), d.paymentTerms());
            text(cell(sheet, 31 + shift, 2), join(" ", d.bankName(), d.bankAddress()));
            text(cell(sheet, 32 + shift, 2), d.bankAccount());
            text(cell(sheet, 33 + shift, 2), blank(d.beneficiary()) ? d.sellerName() : d.beneficiary());
            text(cell(sheet, 34 + shift, 2), d.sellerAddress());
        }
        text(cell(sheet, 41 + shift, 1), date);
        text(cell(sheet, 41 + shift, 6), date);
        sheet.setFitToPage(true);
        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        book.setPrintArea(0, 0, LAST_COL, 0, 41 + shift);
        PiSheetPdfRenderer.fitRows(book, sheet, 0, LAST_COL);
        if (PiSheetPdfRenderer.nearlySinglePage(sheet, 0, LAST_COL, printLastRow(book))) sheet.getPrintSetup().setFitHeight((short) 1);
        book.setForceFormulaRecalculation(false);
    }

    private static void addAdjustment(List<Object[]> values, String label, BigDecimal amount) {
        if (amount.signum() != 0) values.add(new Object[]{label, amount});
    }

    private static boolean contains(String text, String part) {
        return !blank(text) && !blank(part) && text.toLowerCase(Locale.ROOT).contains(part.strip().toLowerCase(Locale.ROOT));
    }
}
