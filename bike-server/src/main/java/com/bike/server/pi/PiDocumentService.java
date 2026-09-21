package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Renders only saved PI values. It never re-reads mutable customer, SKU or bank master data. */
@Service
public class PiDocumentService {
    private static final Logger log = LoggerFactory.getLogger(PiDocumentService.class);
    private static final Color INK = new Color(28, 49, 66);
    private static final Color PALE = new Color(235, 241, 245);

    public byte[] excel(PiView pi) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("PI");
            int[] widths = {18, 35, 10, 24, 24, 24, 26};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            Font font = book.createFont(); font.setFontName("Noto Sans SC"); font.setFontHeightInPoints((short) 10);
            CellStyle body = book.createCellStyle(); body.setFont(font); body.setWrapText(true);
            body.setVerticalAlignment(VerticalAlignment.TOP);
            CellStyle heading = book.createCellStyle(); heading.cloneStyleFrom(body);
            Font bold = book.createFont(); bold.setFontName("Noto Sans SC"); bold.setBold(true); bold.setFontHeightInPoints((short) 11);
            heading.setFont(bold); heading.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            int row = 0;
            for (String text : header(pi)) row = merged(sheet, row, text, row == 0 ? heading : body);
            row++;
            int tableHead = row;
            String[] columns = {"SKU", "商品 / 规格", "数量", "单价", "行折扣", "行税额", "行金额"};
            Row head = sheet.createRow(row++); head.setHeightInPoints(25);
            for (int i = 0; i < columns.length; i++) cell(head, i, columns[i], heading);
            for (PiItemView item : pi.getItems()) {
                Row r = sheet.createRow(row++);
                String product = text(item.getProductName()) + "\n" + text(item.getSpecification());
                r.setHeightInPoints(Math.max(32, Math.min(400, 15 * (2 + product.length() / 20))));
                cell(r, 0, item.getSkuCode(), body); cell(r, 1, product, body);
                Cell qty = r.createCell(2); qty.setCellValue(item.getQuantity()); qty.setCellStyle(body);
                // Excel numbers have only 15 significant digits. Store currency as exact decimal text.
                cell(r, 3, money(item.getUnitPrice()), body); cell(r, 4, money(item.getDiscountAmount()), body);
                cell(r, 5, money(item.getTaxAmount()), body); cell(r, 6, money(item.getLineTotal()), body);
            }
            row++;
            for (String text : totals(pi)) row = merged(sheet, row, text, heading);
            for (String text : terms(pi)) row = merged(sheet, row, text, body);
            merged(sheet, row, "金额按单据币种显示，保留精确十进制文本；总额含整单折扣、税额及运费。", body);
            sheet.createFreezePane(2, tableHead + 1);
            sheet.setRepeatingRows(new CellRangeAddress(tableHead, tableHead, -1, -1));
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setFitWidth((short) 1); sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.getFooter().setLeft(pi.getPiNo() + " / R" + pi.getRevision());
            sheet.getFooter().setRight("Page &P / &N");
            book.setPrintArea(0, 0, 6, 0, sheet.getLastRowNum());
            book.write(out); return out.toByteArray();
        } catch (IOException e) {
            log.warn("PI Excel rendering failed for {}", pi.getId(), e);
            throw new BusinessException("PI_EXPORT_FAILED", "PI Excel 生成失败，请重试");
        }
    }

    public byte[] pdf(PiView pi) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             InputStream source = getClass().getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if (source == null) throw new IOException("Bundled PI font is missing");
            PDType0Font font = PDType0Font.load(document, source, true);
            document.getDocumentInformation().setTitle("PI " + pi.getPiNo() + " R" + pi.getRevision());
            document.getDocumentInformation().setSubject("Proforma Invoice");
            try (Layout layout = new Layout(document, font, pi)) {
                for (String line : header(pi)) layout.paragraph(line, 10, 5);
                layout.gap(10);
                layout.tableHeader();
                for (PiItemView item : pi.getItems()) {
                    layout.item(item);
                }
                layout.gap(12);
                for (String line : totals(pi)) layout.paragraph(line, 10, 4);
                layout.gap(10);
                for (String line : terms(pi)) layout.paragraph(line, 9, 4);
            }
            int count = document.getNumberOfPages();
            for (int index = 0; index < count; index++) {
                try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(index),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    draw(stream, font, "Page " + (index + 1) + " / " + count, 8, 493, 26);
                }
            }
            document.save(out); return out.toByteArray();
        } catch (IllegalArgumentException e) {
            log.warn("PI PDF unsupported character for {}", pi.getId());
            throw new BusinessException("PI_PDF_CHARACTER_UNSUPPORTED", "PI 包含字体不支持的字符，请核对单据文字");
        } catch (IOException e) {
            log.warn("PI PDF rendering failed for {}", pi.getId(), e);
            throw new BusinessException("PI_EXPORT_FAILED", "PI PDF 生成失败，请重试");
        }
    }

    private static List<String> header(PiView p) {
        List<String> lines = new ArrayList<>();
        lines.add("PROFORMA INVOICE / 形式发票");
        lines.add("PI: " + p.getPiNo() + "    Revision: " + p.getRevision() + "    " + documentState(p));
        lines.add("贸易类型: " + text(p.getTradeType()) + "    币种: " + text(p.getCurrencyCode())
                + "    计划发货: " + Objects.toString(p.getPlannedShipDate(), "未指定"));
        PiDocumentSnapshot d = p.getDocument();
        if (d != null) {
            lines.add("卖方 / Seller: " + text(d.sellerName()));
            add(lines, "卖方地址: ", d.sellerAddress()); add(lines, "卖方联系方式: ", d.sellerContact());
        }
        lines.add("买方 / Buyer: " + text(p.getCustomerName()) + "    " + text(p.getCustomerCode()));
        if (d != null) {
            add(lines, "买方地址: ", d.buyerAddress()); add(lines, "买方联系方式: ", d.buyerContact());
        }
        return lines;
    }

    private static List<String> totals(PiView p) {
        List<String> values = new ArrayList<>(List.of("商品小计 / Subtotal: " + money(p.getSubtotal()),
                "折扣合计 / Discount: " + money(p.getDiscountAmount()) + "    税额合计 / Tax: " + money(p.getTaxAmount()),
                "运费 / Freight: " + money(p.getFreightAmount()),
                "应付总额 / Total (" + text(p.getCurrencyCode()) + "): " + money(p.getTotalAmount())));
        if (p.getDepositRequiredAmount() != null) values.add("定金门槛 / Deposit required: " + money(p.getDepositRequiredAmount()));
        if (p.getShipmentRequiredAmount() != null) values.add("发货门槛 / Shipment required: " + money(p.getShipmentRequiredAmount()));
        return values;
    }

    private static List<String> terms(PiView p) {
        List<String> lines = new ArrayList<>();
        PiDocumentSnapshot d = p.getDocument();
        if (d != null) {
            add(lines, "付款条款 / Payment terms: ", d.paymentTerms());
            add(lines, "交付条款 / Delivery terms: ", d.deliveryTerms());
            add(lines, "收款银行 / Bank: ", d.bankName());
            add(lines, "账户 / Account: ", d.bankAccount());
            add(lines, "收款人 / Beneficiary: ", d.beneficiary());
            lines.add("模板: " + text(d.templateCode()) + " / " + text(d.templateVersion()));
        }
        add(lines, "备注 / Remarks: ", p.getRemark());
        return lines;
    }

    private static String documentState(PiView p) {
        return "DRAFT".equals(p.getStatus()) || p.getDocument() == null
                ? "草稿 / DRAFT" : "状态 / Status: " + text(p.getStatus());
    }

    private static void add(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) lines.add(label + value);
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static String money(BigDecimal value) { return value == null ? "未指定" : value.toPlainString(); }
    private static void cell(Row row, int col, String text, CellStyle style) {
        Cell c = row.createCell(col); c.setCellValue(text(text)); c.setCellStyle(style);
    }
    private static int merged(Sheet sheet, int row, String text, CellStyle style) {
        // Split long terms into separate rows to avoid Excel's 409-point row height limit.
        for (String paragraph : text.split("\\R", -1)) {
            for (int offset = 0; offset < Math.max(1, paragraph.length()); offset += 180) {
                String part = paragraph.substring(offset, Math.min(paragraph.length(), offset + 180));
                Row r = sheet.createRow(row); r.setHeightInPoints(18 * (1 + part.length() / 85));
                cell(r, 0, part, style); sheet.addMergedRegion(new CellRangeAddress(row, row, 0, 6)); row++;
            }
        }
        return row;
    }

    private static void draw(PDPageContentStream stream, PDType0Font font, String text,
                             float size, float x, float y) throws IOException {
        stream.setNonStrokingColor(INK); stream.beginText(); stream.setFont(font, size);
        stream.newLineAtOffset(x, y); stream.showText(text); stream.endText();
    }

    private static final class Layout implements AutoCloseable {
        private static final float LEFT = 40, WIDTH = 515, BOTTOM = 54;
        private final PDDocument document;
        private final PDType0Font font;
        private final PiView pi;
        private PDPageContentStream stream;
        private float y;
        Layout(PDDocument document, PDType0Font font, PiView pi) throws IOException {
            this.document = document; this.font = font; this.pi = pi; newPage();
        }
        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4); document.addPage(page);
            stream = new PDPageContentStream(document, page); y = 797;
            draw(stream, font, pi.getPiNo() + " / R" + pi.getRevision() + " / " + documentState(pi), 8, LEFT, 818);
            stream.setStrokingColor(PALE); stream.moveTo(LEFT, 808); stream.lineTo(LEFT + WIDTH, 808); stream.stroke();
        }
        private void ensure(float height) throws IOException { if (y - height < BOTTOM) newPage(); }
        void gap(float height) throws IOException { ensure(height); y -= height; }
        void paragraph(String value, float size, float after) throws IOException {
            for (String line : wrap(value, size, WIDTH)) { ensure(size + 5); draw(stream, font, line, size, LEFT, y - size); y -= size + 5; }
            gap(after);
        }
        List<String> wrap(String value, float size, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String part : value.replace('\t', ' ').split("\\R", -1)) {
                StringBuilder current = new StringBuilder(); float used = 0;
                for (int point : part.codePoints().toArray()) {
                    if (Character.isISOControl(point)) continue;
                    String glyph = new String(Character.toChars(point));
                    float advance = font.getStringWidth(glyph) / 1000 * size;
                    if (used + advance > width && !current.isEmpty()) { lines.add(current.toString()); current.setLength(0); used = 0; }
                    current.append(glyph); used += advance;
                }
                lines.add(current.toString());
            }
            return lines;
        }
        void tableHeader() throws IOException {
            ensure(28); stream.setNonStrokingColor(PALE); stream.addRect(LEFT, y - 23, WIDTH, 23); stream.fill();
            draw(stream, font, "商品 / SKU", 9, LEFT + 6, y - 15);
            draw(stream, font, "数量", 9, 288, y - 15);
            draw(stream, font, "单价", 9, 342, y - 15);
            draw(stream, font, "行金额", 9, 461, y - 15); y -= 27;
        }
        void item(PiItemView item) throws IOException {
            List<String> identity = wrap(text(item.getSkuCode()) + "  " + text(item.getProductName()), 9, 237);
            identity.addAll(wrap(text(item.getSpecification()), 8, 237));
            if (y - Math.min(identity.size() * 13 + 35, 80) < BOTTOM) { newPage(); tableHeader(); }
            draw(stream, font, String.valueOf(item.getQuantity()), 8, 288, y - 9);
            draw(stream, font, money(item.getUnitPrice()), 8, 342, y - 9);
            draw(stream, font, money(item.getLineTotal()), 8, 461, y - 9);
            for (String line : identity) {
                if (y - 13 < BOTTOM) { newPage(); tableHeader(); }
                draw(stream, font, line, 9, LEFT + 6, y - 9); y -= 13;
            }
            if (y - 24 < BOTTOM) { newPage(); tableHeader(); }
            draw(stream, font, "行折扣: " + money(item.getDiscountAmount()) + "    行税额: " + money(item.getTaxAmount()), 8, LEFT + 6, y - 10);
            y -= 23; stream.setStrokingColor(PALE); stream.moveTo(LEFT, y); stream.lineTo(LEFT + WIDTH, y); stream.stroke(); y -= 7;
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }
}
