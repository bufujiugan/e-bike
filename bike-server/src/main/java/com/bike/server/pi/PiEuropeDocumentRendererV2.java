package com.bike.server.pi;

import static com.bike.server.pi.PiSheets.*;

import com.bike.server.common.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.LoggerFactory;

/**
 * PI_EUROPE_V2：France_1104_PI 原件版式。相对 V1 修正开单日期被版本状态覆盖、恢复原件的
 * KVK-No./VAT/IBAN/SWIFT 栏目与 “No.” 编号写法，并补入卖方登记号、VAT、SWIFT、买方 VAT 和发货地址。
 * 商品金额与底部调整行按 312605030_Italy_DSB_Commercial Invoice：Nett/Gross/Total 为 Excel 公式，折扣与运费分行列示。
 * 已归档或已入队的 PI_EUROPE_V1 仍由 {@link PiEuropeDocumentRenderer} 按原版本生成。
 */
final class PiEuropeDocumentRendererV2 {
    static final String VERSION = "PI_EUROPE_V2";
    // 0 起始行号：表头（Excel 32）、商品首行（33）、合计（34/35）、Downpayment invoices（37）。
    private static final int HEADER_ROW = 31, ITEM_ROW = 32, TOTAL_ROW = 33, DOWNPAYMENT_ROW = 36, LAST_COL = 11;

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
                    HEADER_ROW, HEADER_ROW, findRow(sheet, 8, "Total Price", lastRow), "Proforma Invoice " + pi.getPiNo(), null, true));
        } catch (IOException | RuntimeException error) { throw failed(pi, error); }
    }

    private BusinessException failed(PiView pi, Exception error) {
        LoggerFactory.getLogger(getClass()).warn("Europe PI V2 rendering failed, piId={}", pi.getId(), error);
        return new BusinessException("PI_EXPORT_FAILED", "欧洲 PI 文件生成失败，请重试");
    }

    XSSFWorkbook workbook(PiView pi) throws IOException {
        try (var input = getClass().getResourceAsStream("/templates/europe-pi-france.xlsx")) {
            if (input == null) throw new IOException("Europe PI layout is missing");
            var book = new XSSFWorkbook(input);
            try { populate(book, pi); return book; }
            catch (IOException | RuntimeException error) { book.close(); throw error; }
        }
    }

    private void populate(XSSFWorkbook book, PiView pi) throws IOException {
        var sheet = book.getSheetAt(0);
        book.setSheetName(0, "Proforma Invoice");
        sheet.setDisplayGridlines(false);
        var d = pi.getDocument();
        String currency = text(pi.getCurrencyCode()).toUpperCase(Locale.ROOT);
        CellStyle[] itemStyles = styles(sheet, ITEM_ROW, LAST_COL + 1);
        CellStyle totalLabel = cell(sheet, TOTAL_ROW, 8).getCellStyle(), totalAmount = cell(sheet, TOTAL_ROW, 10).getCellStyle();
        CellStyle downpayment = cell(sheet, DOWNPAYMENT_ROW, 0).getCellStyle();
        float itemHeight = sheet.getRow(ITEM_ROW).getHeightInPoints();
        // 原件金额格式固定为欧元符号；其他币种使用同样的两位小数并显示币种代码。
        String moneyFormat = "EUR".equals(currency) ? itemStyles[5].getDataFormatString() : "\"" + currency + "\" #,##0.00;[Red]\"" + currency + "\" -#,##0.00";
        short money = book.createDataFormat().getFormat(moneyFormat);
        // 原件商品说明与其他列一样水平居中。
        CellStyle description = derived(book, itemStyles[0], style -> { style.setAlignment(HorizontalAlignment.CENTER); style.setVerticalAlignment(VerticalAlignment.CENTER); style.setWrapText(true); });
        CellStyle count = derived(book, itemStyles[4], style -> style.setVerticalAlignment(VerticalAlignment.CENTER));
        CellStyle price = derived(book, itemStyles[5], style -> { style.setDataFormat(money); style.setVerticalAlignment(VerticalAlignment.CENTER); });
        CellStyle percent = derived(book, itemStyles[7], style -> { style.setDataFormat(book.createDataFormat().getFormat("0%")); style.setVerticalAlignment(VerticalAlignment.CENTER); });
        CellStyle percentDetail = derived(book, percent, style -> style.setDataFormat(book.createDataFormat().getFormat("0.00%")));
        // 原件 Credit / Freight Cost 行与商品行同字体，说明在 A:J 合并居中，金额在 K:L。
        CellStyle adjustmentLabel = derived(book, itemStyles[0], style -> { style.setAlignment(HorizontalAlignment.CENTER); style.setVerticalAlignment(VerticalAlignment.CENTER); });
        CellStyle adjustmentAmount = derived(book, price, style -> {});
        CellStyle grandTotal = derived(book, totalAmount, style -> style.setDataFormat(money));
        removeRowsFrom(sheet, ITEM_ROW);

        text(sheet, "H3", "No." + text(pi.getPiNo()) + (pi.getRevision() > 1 ? " R" + pi.getRevision() : ""));
        var issued = d != null && d.issueDate() != null ? d.issueDate() : pi.getCreatedAt() == null ? null : pi.getCreatedAt().toLocalDate();
        if (issued != null) {
            var date = cell(sheet, "I4");
            date.setCellValue(issued);
            date.setCellStyle(derived(book, date.getCellStyle(), style -> style.setDataFormat(book.createDataFormat().getFormat("dd.mm.yyyy"))));
        }
        text(sheet, "B9", d == null ? "" : d.sellerName());
        text(sheet, "H9", pi.getCustomerName());
        text(sheet, "I10", pi.getCustomerCode());
        if (d != null) {
            text(sheet, "C10", d.sellerRegistrationNo());
            text(sheet, "C11", d.sellerVatNo());
            text(sheet, "C12", d.sellerEmail());
            text(sheet, "C13", d.sellerPhone());
            text(sheet, "C14", d.bankName());
            // 原件为 IBAN；非 IBAN 格式的账号如实标注为 Account，避免错误标示。
            text(sheet, "B15", iban(d.bankAccount()) ? "IBAN: " : "Account: ");
            text(sheet, "C15", d.bankAccount());
            text(sheet, "C16", d.swiftCode());
            if (!blank(d.beneficiary()) && !d.beneficiary().strip().equalsIgnoreCase(text(d.sellerName()).strip())) {
                text(sheet, "B17", "Payee: ");
                text(sheet, "C17", d.beneficiary());
            }
            text(sheet, "I11", d.buyerVatNo());
            text(sheet, "I12", d.buyerEmail());
            text(sheet, "I13", d.buyerPhone());
            text(sheet, "B21", join("\n", d.sellerName(), d.sellerAddress(), contains(d.sellerAddress(), d.sellerCountry()) ? null : d.sellerCountry()));
            text(sheet, "H21", join("\n", d.buyerAddress(), contains(d.buyerAddress(), d.buyerCountry()) ? null : d.buyerCountry()));
            text(sheet, "B27", d.dispatchAddress());
            text(sheet, "H27", d.shippingAddress());
        }
        for (String address : List.of("B21", "H21", "B27", "H27"))
            cell(sheet, address).setCellStyle(derived(book, cell(sheet, address).getCellStyle(), style -> { style.setWrapText(true); style.setVerticalAlignment(VerticalAlignment.TOP); }));
        for (String address : List.of("C10", "C11", "C12", "C13", "C14", "C15", "C16", "C17", "I10", "I11", "I12", "I13"))
            cell(sheet, address).setCellStyle(derived(book, cell(sheet, address).getCellStyle(), style -> style.setWrapText(true)));
        if (d != null && text(d.sellerName()).toLowerCase(Locale.ROOT).startsWith("teewing")) logo(book, sheet);
        text(sheet, "F32", "Unit Price\n(" + currency + ")");
        text(sheet, "I32", "Price Nett\n(" + currency + ")");
        text(sheet, "K32", "Price Gross\n(" + currency + ")");

        // 按 312605030_Italy_DSB_Commercial Invoice 原件：Price Nett = 数量 × 单价，Price Gross = Nett × (1 + VAT %)，
        // 折扣（原件 Credit）与运费（Freight Cost）各占一行写在商品下方，Total Price = SUM(Gross 列)。
        int row = ITEM_ROW;
        boolean formulas = true, discountIncludesVat = false;
        BigDecimal grossDiscount = BigDecimal.ZERO, unratedTax = BigDecimal.ZERO, expected = BigDecimal.ZERO;
        for (var item : pi.getItems()) {
            Row target = sheet.createRow(row);
            target.setHeightInPoints(itemHeight);
            for (int col = 0; col <= LAST_COL; col++) target.createCell(col).setCellStyle(itemStyles[col]);
            target.getCell(0).setCellStyle(description);
            for (int col : new int[]{5, 8, 10}) target.getCell(col).setCellStyle(price);
            target.getCell(4).setCellStyle(count);
            merge(sheet, row, 0, 3); merge(sheet, row, 5, 6); merge(sheet, row, 8, 9); merge(sheet, row, 10, 11);
            text(target.getCell(0), join("\n", item.getProductName(), same(item.getProductName(), item.getSpecification()) ? null : item.getSpecification()));
            target.getCell(4).setCellValue(item.getQuantity());
            number(target.getCell(5), item.getUnitPrice());
            BigDecimal raw = amount(item.getUnitPrice()).multiply(BigDecimal.valueOf(item.getQuantity()));
            BigDecimal discount = amount(item.getDiscountAmount()), tax = amount(item.getTaxAmount()), base = raw.subtract(discount);
            // 系统按行保存税额：VAT % = 行税额 ÷ 折后净额；折后净额为零时税额无法折成税率，改列入下方 VAT 行。
            BigDecimal rate = BigDecimal.ZERO;
            if (tax.signum() != 0 && base.signum() > 0) rate = tax.divide(base, 12, RoundingMode.HALF_UP).stripTrailingZeros();
            else unratedTax = unratedTax.add(tax);
            target.getCell(7).setCellStyle(rate.movePointRight(2).stripTrailingZeros().scale() <= 0 ? percent : percentDetail);
            target.getCell(7).setCellValue(rate.doubleValue());
            BigDecimal gross = raw.multiply(BigDecimal.ONE.add(rate));
            if (exact(item.getUnitPrice()) && exact(raw)) {
                int excelRow = row + 1;
                target.getCell(8).setCellFormula("E" + excelRow + "*F" + excelRow);
                target.getCell(10).setCellFormula("I" + excelRow + "*(1+H" + excelRow + ")");
            } else {
                formulas = false;
                number(target.getCell(8), raw);
                number(target.getCell(10), excel(gross));
            }
            BigDecimal lineDiscount = discount.multiply(BigDecimal.ONE.add(rate));
            if (discount.signum() != 0 && rate.signum() != 0) discountIncludesVat = true;
            grossDiscount = grossDiscount.add(lineDiscount);
            expected = expected.add(gross).subtract(lineDiscount);
            row++;
        }
        BigDecimal lineDiscount = pi.getItems().stream().map(item -> amount(item.getDiscountAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lineTax = pi.getItems().stream().map(item -> amount(item.getTaxAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discountRow = grossDiscount.add(amount(pi.getDiscountAmount()).subtract(lineDiscount)).negate();
        BigDecimal vatRow = amount(pi.getTaxAmount()).subtract(lineTax).add(unratedTax);
        BigDecimal freight = amount(pi.getFreightAmount()), otherFees = amount(pi.getOtherFeeAmount());
        expected = expected.add(amount(pi.getDiscountAmount()).subtract(lineDiscount).negate()).add(vatRow).add(freight).add(otherFees);
        row = adjustment(sheet, row, discountIncludesVat ? "Discount (incl. VAT)" : "Discount", discountRow, adjustmentLabel, adjustmentAmount);
        row = adjustment(sheet, row, "VAT", vatRow, adjustmentLabel, adjustmentAmount);
        row = adjustment(sheet, row, "Freight Cost", freight, adjustmentLabel, adjustmentAmount);
        row = adjustment(sheet, row, "Other Fees", otherFees, adjustmentLabel, adjustmentAmount);
        Row totalRow = sheet.createRow(row);
        totalRow.setHeightInPoints(21.6f);
        for (int col = 8; col <= LAST_COL; col++) totalRow.createCell(col).setCellStyle(col < 10 ? totalLabel : grandTotal);
        merge(sheet, row, 8, 9); merge(sheet, row, 10, 11);
        text(totalRow.getCell(8), "Total Price");
        // 公式合计与系统应付总额核对一致才写公式；任何不一致都以系统金额为准，避免单据金额与系统不符。
        boolean reconciled = expected.subtract(amount(pi.getTotalAmount())).abs().compareTo(new BigDecimal("0.005")) < 0;
        if (formulas && reconciled && !pi.getItems().isEmpty()) totalRow.getCell(10).setCellFormula("SUM(K" + (ITEM_ROW + 1) + ":L" + row + ")");
        else {
            if (!reconciled) LoggerFactory.getLogger(getClass()).warn("Europe PI amounts do not reconcile, piId={}, rows={}, total={}", pi.getId(), expected, pi.getTotalAmount());
            number(totalRow.getCell(10), pi.getTotalAmount());
        }
        row += 3;
        Row downpaymentRow = sheet.createRow(row);
        downpaymentRow.createCell(0).setCellStyle(downpayment);
        text(downpaymentRow.getCell(0), "Downpayment invoices");
        row += 2;

        var terms = new ArrayList<String>();
        if (d != null) {
            add(terms, "Payment Terms: ", d.paymentTerms()); add(terms, "Delivery Terms: ", d.deliveryTerms());
            add(terms, "Trade Terms: ", d.tradeTerms()); add(terms, "Transport: ", d.transportMode());
            add(terms, "Supplier contact: ", join(" / ", d.sellerContactName(), d.sellerContact()));
            add(terms, "Customer contact: ", join(" / ", d.buyerContactName(), d.buyerContact()));
            if (d.validUntil() != null) terms.add("Valid until: " + d.validUntil().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        }
        add(terms, "Remarks: ", pi.getRemark());
        CellStyle termStyle = derived(book, downpayment, style -> { style.setWrapText(true); style.setVerticalAlignment(VerticalAlignment.TOP); });
        for (String term : terms) {
            Row target = sheet.createRow(row);
            for (int col = 0; col <= LAST_COL; col++) target.createCell(col).setCellStyle(termStyle);
            merge(sheet, row, 0, LAST_COL);
            text(target.getCell(0), term);
            target.setHeightInPoints(17);
            row++;
        }
        sheet.setRepeatingRows(new CellRangeAddress(HEADER_ROW, HEADER_ROW, 0, LAST_COL));
        sheet.setFitToPage(true); sheet.setHorizontallyCenter(true);
        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.getPrintSetup().setLandscape(false);
        sheet.getPrintSetup().setFitWidth((short) 1); sheet.getPrintSetup().setFitHeight((short) 0);
        book.setPrintArea(0, 0, LAST_COL, 0, row - 1);
        // 写入公式缓存值：Excel 打开即显示金额，PDF 与预览按同一结果绘制。
        book.getCreationHelper().createFormulaEvaluator().evaluateAll();
        book.setForceFormulaRecalculation(true);
        PiSheetPdfRenderer.fitRows(book, sheet, 0, LAST_COL);
        if (PiSheetPdfRenderer.nearlySinglePage(sheet, 0, LAST_COL, printLastRow(book))) sheet.getPrintSetup().setFitHeight((short) 1);
    }

    private static int adjustment(Sheet sheet, int row, String label, BigDecimal value, CellStyle labelStyle, CellStyle amountStyle) {
        if (value.signum() == 0) return row;
        Row target = sheet.createRow(row);
        target.setHeightInPoints(21.6f);
        for (int col = 0; col <= LAST_COL; col++) target.createCell(col).setCellStyle(col < 10 ? labelStyle : amountStyle);
        merge(sheet, row, 0, 9); merge(sheet, row, 10, 11);
        text(target.getCell(0), label);
        number(target.getCell(10), excel(value));
        return row + 1;
    }

    /** 公式引用的数值须能被 Excel 双精度准确表示；超过时该行改写计算结果。 */
    private static boolean exact(BigDecimal value) { return value != null && value.stripTrailingZeros().precision() <= 15; }

    /** 含税折扣等派生金额只保留 Excel 可准确表示的 15 位有效数字，避免被写成文本。 */
    private static BigDecimal excel(BigDecimal value) { return value.round(new java.math.MathContext(15, RoundingMode.HALF_UP)); }

    private static void logo(XSSFWorkbook book, Sheet sheet) throws IOException {
        try (var logo = PiEuropeDocumentRendererV2.class.getResourceAsStream("/pi/teewing-logo.png")) {
            if (logo == null) throw new IOException("PI brand image is missing");
            var anchor = book.getCreationHelper().createClientAnchor();
            anchor.setCol1(0); anchor.setRow1(0); anchor.setCol2(6); anchor.setRow2(4);
            sheet.createDrawingPatriarch().createPicture(anchor, book.addPicture(logo.readAllBytes(), Workbook.PICTURE_TYPE_PNG));
        }
    }

    static boolean iban(String account) {
        String value = text(account).replace(" ", "").toUpperCase(Locale.ROOT);
        return value.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}");
    }

    private static void add(List<String> values, String label, String value) { if (!blank(value)) values.add(label + value.strip()); }

    private static boolean contains(String text, String part) {
        return !blank(text) && !blank(part) && text.toLowerCase(Locale.ROOT).contains(part.strip().toLowerCase(Locale.ROOT));
    }

    private static boolean same(String left, String right) {
        return blank(right) || !blank(left) && left.strip().equalsIgnoreCase(right.strip());
    }
}
