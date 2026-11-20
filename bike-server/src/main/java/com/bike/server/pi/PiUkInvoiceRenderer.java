package com.bike.server.pi;

import static com.bike.server.pi.PiSheets.*;

import com.bike.server.common.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.LoggerFactory;

/**
 * PI_V6：以用户指定的 260728英国builtPI.xlsx 为底板（templates/uk-built-pi.xlsx，已清除示例客户、价格和银行资料），
 * 只填入本单快照。版面、列宽、字体、蓝色单号栏、五列商品表、FOB 行及银行资料块与原件一致；
 * 折扣、税费、运费及其他费用仅在有金额时按原件合计行样式追加。
 */
final class PiUkInvoiceRenderer {
    static final String VERSION = "PI_V6";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    // 0 起始行号，对应原件 Excel 行 18（商品首行）、24（Net total）、25（FOB）、26/27（银行资料）。
    private static final int ITEM_ROW = 17, TOTAL_ROW = 23, TERMS_ROW = 24, BANK_FIRST_ROW = 25, BANK_ROW = 26, HEADER_ROW = 16;

    byte[] excel(PiView pi) {
        try (var book = workbook(pi); var output = new ByteArrayOutputStream()) {
            book.write(output);
            return output.toByteArray();
        } catch (IOException | RuntimeException error) { throw failed(pi, error); }
    }

    byte[] pdf(PiView pi) {
        try (var book = workbook(pi)) {
            var sheet = book.getSheetAt(0);
            int last = printLastRow(book);
            return new PiSheetPdfRenderer().render(book, sheet, new PiSheetPdfRenderer.Options(0, 4, last,
                    HEADER_ROW, HEADER_ROW, findRow(sheet, 0, "Net total", last), "Proforma Invoice " + pi.getPiNo(), null, true));
        } catch (IOException | RuntimeException error) { throw failed(pi, error); }
    }

    private BusinessException failed(PiView pi, Exception error) {
        LoggerFactory.getLogger(getClass()).warn("UK reference PI rendering failed, piId={}", pi.getId(), error);
        return new BusinessException("PI_EXPORT_FAILED", "PI 文件生成失败，请重试");
    }

    XSSFWorkbook workbook(PiView pi) throws IOException {
        try (var input = getClass().getResourceAsStream("/templates/uk-built-pi.xlsx")) {
            if (input == null) throw new IOException("UK PI layout is missing");
            var book = new XSSFWorkbook(input);
            try { populate(book, pi); return book; }
            catch (IOException | RuntimeException error) { book.close(); throw error; }
        }
    }

    private void populate(XSSFWorkbook book, PiView pi) throws IOException {
        var sheet = book.getSheetAt(0);
        var d = pi.getDocument();
        String currency = text(pi.getCurrencyCode()).toUpperCase(Locale.ROOT);
        CellStyle[] item = styles(sheet, ITEM_ROW, 5), total = styles(sheet, TOTAL_ROW, 5), terms = styles(sheet, TERMS_ROW, 5);
        CellStyle bankFirst = cell(sheet, BANK_FIRST_ROW, 0).getCellStyle(), bank = cell(sheet, BANK_ROW, 0).getCellStyle();
        CellStyle[] bankFirstRow = styles(sheet, BANK_FIRST_ROW, 5);
        float itemHeight = sheet.getRow(ITEM_ROW).getHeightInPoints(), lineHeight = sheet.getRow(BANK_ROW).getHeightInPoints();
        // 金额列沿用原件数字格式；商品说明左对齐便于多行阅读，其余与原件一致。
        CellStyle description = derived(book, item[1], style -> style.setAlignment(HorizontalAlignment.LEFT));
        CellStyle amountTotal = derived(book, total[4], style -> style.setDataFormat(book.createDataFormat().getFormat("#,##0.00")));
        CellStyle amountLine = derived(book, total[4], style -> {
            style.setDataFormat(book.createDataFormat().getFormat("#,##0.00"));
            style.setFont(book.getFontAt(item[4].getFontIndex()));
        });
        CellStyle totalLabel = total[0];
        removeRowsFrom(sheet, ITEM_ROW);

        labeled(book, cell(sheet, "A6"), "To: ", pi.getCustomerName());
        text(sheet, "D6", d == null ? "" : d.sellerName());
        if (d != null) {
            text(sheet, "A7", join("\n", d.buyerAddress(), contains(d.buyerAddress(), d.buyerCountry()) ? null : d.buyerCountry(),
                    blank(d.buyerContactName()) ? null : "Contact: " + d.buyerContactName(),
                    blank(d.buyerPhone()) ? null : "Phone: " + d.buyerPhone(), blank(d.buyerEmail()) ? null : "Email: " + d.buyerEmail(),
                    blank(d.buyerVatNo()) ? null : "VAT: " + d.buyerVatNo()));
            text(sheet, "D7", join("\n", d.sellerAddress(), contains(d.sellerAddress(), d.sellerCountry()) ? null : d.sellerCountry(),
                    blank(d.sellerPhone()) ? null : "Phone: " + d.sellerPhone(), blank(d.sellerEmail()) ? null : "Mail: " + d.sellerEmail()));
            text(sheet, "A12", d.productCategory());
        }
        text(sheet, "A15", pi.getSourceOrderNo());
        text(sheet, "B15", pi.getPiNo() + (pi.getRevision() > 1 ? " R" + pi.getRevision() : ""));
        var issued = d != null && d.issueDate() != null ? d.issueDate() : pi.getCreatedAt() == null ? null : pi.getCreatedAt().toLocalDate();
        text(sheet, "C15", issued == null ? "" : DATE.format(issued));
        text(sheet, "D17", "Unit Price (" + currency + ")");
        text(sheet, "E17", "Total Amount (" + currency + ")");
        if (d != null && text(d.sellerName()).toLowerCase(Locale.ROOT).startsWith("teewing")) logo(book, sheet);

        int row = ITEM_ROW, index = 1, quantity = 0;
        for (var line : pi.getItems()) {
            Row target = sheet.createRow(row);
            target.setHeightInPoints(itemHeight);
            for (int col = 0; col < 5; col++) target.createCell(col).setCellStyle(col == 1 ? description : item[col]);
            target.getCell(0).setCellValue(index++);
            text(target.getCell(1), join("\n", line.getProductName(), same(line.getProductName(), line.getSpecification()) ? null : line.getSpecification()));
            target.getCell(2).setCellValue(line.getQuantity());
            number(target.getCell(3), line.getUnitPrice());
            number(target.getCell(4), amount(line.getUnitPrice()).multiply(BigDecimal.valueOf(line.getQuantity())));
            quantity += line.getQuantity();
            row++;
        }
        row = adjustment(sheet, row, "Discount", amount(pi.getDiscountAmount()).negate(), total, totalLabel, amountLine, itemHeight);
        row = adjustment(sheet, row, "Tax", amount(pi.getTaxAmount()), total, totalLabel, amountLine, itemHeight);
        row = adjustment(sheet, row, "Freight", amount(pi.getFreightAmount()), total, totalLabel, amountLine, itemHeight);
        row = adjustment(sheet, row, "Other fees", amount(pi.getOtherFeeAmount()), total, totalLabel, amountLine, itemHeight);
        Row net = sheet.createRow(row++);
        net.setHeightInPoints(itemHeight);
        for (int col = 0; col < 5; col++) net.createCell(col).setCellStyle(col == 0 ? totalLabel : col == 4 ? amountTotal : total[col]);
        net.getCell(0).setCellValue("Net total");
        net.getCell(2).setCellValue(quantity);
        number(net.getCell(4), pi.getTotalAmount());

        Row tradeRow = sheet.createRow(row);
        tradeRow.setHeightInPoints(lineHeight);
        for (int col = 0; col < 5; col++) tradeRow.createCell(col).setCellStyle(terms[col]);
        merge(sheet, row, 1, 4);
        text(tradeRow.getCell(1), d == null ? "" : d.tradeTerms());
        row++;

        var lines = new ArrayList<String[]>();
        if (d != null) {
            lines.add(new String[]{"Account Name: ", d.beneficiary()});
            lines.add(new String[]{"Company Address: ", join(" ", d.sellerAddress(), contains(d.sellerAddress(), d.sellerCountry()) ? null : d.sellerCountry())});
            lines.add(new String[]{"Account Number: ", d.bankAccount()});
            lines.add(new String[]{"Bank Name: ", d.bankName()});
            optional(lines, "Bank Address: ", d.bankAddress());
            optional(lines, "SWIFT Code: ", d.swiftCode());
            optional(lines, "Bank Code: ", d.bankCode());
            optional(lines, "Branch Code: ", d.branchCode());
        }
        boolean first = true;
        for (String[] entry : lines) {
            Row target = sheet.createRow(row);
            target.setHeightInPoints(lineHeight);
            for (int col = 0; col < 5; col++) target.createCell(col).setCellStyle(first ? bankFirstRow[col] : bank);
            merge(sheet, row, 0, 4);
            labeled(book, target.getCell(0), entry[0], entry[1]);
            first = false;
            row++;
        }
        // 参考原件没有条款区；本单填写的付款、交付条款与备注在银行资料后按同一样式列出，不遗漏约定。
        var extra = new ArrayList<String[]>();
        if (d != null) {
            optional(extra, "Payment Terms: ", d.paymentTerms());
            optional(extra, "Delivery Terms: ", d.deliveryTerms());
            optional(extra, "Ship to: ", same(d.shippingAddress(), d.buyerAddress()) ? null : d.shippingAddress());
            optional(extra, "Transport: ", d.transportMode());
            if (d.validUntil() != null) extra.add(new String[]{"Valid until: ", DATE.format(d.validUntil())});
        }
        optional(extra, "Remarks: ", pi.getRemark());
        if (!extra.isEmpty()) row++;
        for (String[] entry : extra) {
            Row target = sheet.createRow(row);
            target.setHeightInPoints(lineHeight);
            for (int col = 0; col < 5; col++) target.createCell(col).setCellStyle(bank);
            merge(sheet, row, 0, 4);
            labeled(book, target.getCell(0), entry[0], entry[1]);
            row++;
        }
        sheet.setRepeatingRows(new org.apache.poi.ss.util.CellRangeAddress(HEADER_ROW, HEADER_ROW, 0, 4));
        sheet.setFitToPage(true);
        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.getPrintSetup().setLandscape(false);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        book.setPrintArea(0, 0, 4, 0, row - 1);
        PiSheetPdfRenderer.fitRows(book, sheet, 0, 4);
        if (PiSheetPdfRenderer.nearlySinglePage(sheet, 0, 4, printLastRow(book))) sheet.getPrintSetup().setFitHeight((short) 1);
        book.setForceFormulaRecalculation(false);
    }

    private static int adjustment(Sheet sheet, int row, String label, BigDecimal value, CellStyle[] total, CellStyle labelStyle,
                                  CellStyle amountStyle, float height) {
        if (value.signum() == 0) return row;
        Row target = sheet.createRow(row);
        target.setHeightInPoints(height);
        for (int col = 0; col < 5; col++) target.createCell(col).setCellStyle(col == 0 ? labelStyle : col == 4 ? amountStyle : total[col]);
        target.getCell(0).setCellValue(label);
        number(target.getCell(4), value);
        return row + 1;
    }

    private static void logo(XSSFWorkbook book, Sheet sheet) throws IOException {
        try (var logo = PiUkInvoiceRenderer.class.getResourceAsStream("/pi/teewing-logo-invoice.png")) {
            if (logo == null) throw new IOException("PI brand image is missing");
            int picture = book.addPicture(logo.readAllBytes(), Workbook.PICTURE_TYPE_PNG);
            // 与原件相同的锚定位置：D2 右移至 E3。
            var anchor = book.getCreationHelper().createClientAnchor();
            anchor.setCol1(3); anchor.setDx1(120650); anchor.setRow1(1); anchor.setDy1(31750);
            anchor.setCol2(4); anchor.setDx2(1400735); anchor.setRow2(2); anchor.setDy2(142943);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
            sheet.createDrawingPatriarch().createPicture(anchor, picture);
        }
    }

    private static void optional(List<String[]> lines, String label, String value) {
        if (!blank(value)) lines.add(new String[]{label, value.strip()});
    }

    private static boolean contains(String text, String part) {
        return !blank(text) && !blank(part) && text.toLowerCase(Locale.ROOT).contains(part.strip().toLowerCase(Locale.ROOT));
    }

    private static boolean same(String left, String right) {
        return blank(right) || !blank(left) && left.strip().equalsIgnoreCase(right.strip());
    }
}
