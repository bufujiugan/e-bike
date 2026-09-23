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
    private final PiDocumentRendererV1 legacy=new PiDocumentRendererV1();
    @org.springframework.beans.factory.annotation.Autowired private PiImageService images;
    private static final Logger log = LoggerFactory.getLogger(PiDocumentService.class);
    private static final Color INK = new Color(28, 49, 66);
    private static final Color PALE = new Color(235, 241, 245);

    public byte[] excel(PiView pi) {
        if("PI_V1".equals(pi.getDocumentRenderVersion()))return legacy.excel(pi);
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
            CellStyle amount = book.createCellStyle(); amount.cloneStyleFrom(body);
            amount.setDataFormat(book.createDataFormat().getFormat("#,##0.00"));
            int row = 0;
            for (String text : header(pi)) row = merged(sheet, row, text, row == 0 ? heading : body);
            row++;
            int tableHead = row;
            String[] columns = {"SKU", "商品 / 规格", "数量", "单价", "行折扣", "行税额", "行金额"};
            Row head = sheet.createRow(row++); head.setHeightInPoints(25);
            for (int i = 0; i < columns.length; i++) cell(head, i, columns[i], heading);
            for (PiItemView item : pi.getItems()) {
                Row r = sheet.createRow(row++);
                String product = text(item.getProductName()) + "\n" + text(item.getSpecification())
                        +"\n型号 / Model: "+text(item.getModelName())+"\n单位 / Unit: "+Objects.toString(item.getUnit(),"未记录");
                r.setHeightInPoints(Math.max(32, Math.min(400, 15 * (2 + product.length() / 20))));
                cell(r, 0, item.getSkuCode(), body); cell(r, 1, product, body);
                Cell qty = r.createCell(2); qty.setCellValue(item.getQuantity()); qty.setCellStyle(body);
                amount(r, 3, item.getUnitPrice(), amount); amount(r, 4, item.getDiscountAmount(), amount);
                amount(r, 5, item.getTaxAmount(), amount); amount(r, 6, item.getLineTotal(), amount);
                var thumbnail=thumbnail(item);
                if(thumbnail!=null) {
                    r.setHeightInPoints(Math.max(72,r.getHeightInPoints()));
                    var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(thumbnail,"png",bytes);
                    var helper=book.getCreationHelper();var anchor=helper.createClientAnchor();
                    anchor.setCol1(0);anchor.setCol2(0);anchor.setRow1(r.getRowNum());anchor.setRow2(r.getRowNum());
                    int emu=org.apache.poi.util.Units.EMU_PER_PIXEL;
                    double factor=Math.min(110.0/thumbnail.getWidth(),64.0/thumbnail.getHeight());
                    anchor.setDx1(3*emu);anchor.setDy1(22*emu);anchor.setDx2((int)(3+thumbnail.getWidth()*factor)*emu);anchor.setDy2((int)(22+thumbnail.getHeight()*factor)*emu);
                    var drawing=(org.apache.poi.xssf.usermodel.XSSFDrawing)sheet.createDrawingPatriarch();
                    drawing.createPicture(anchor,book.addPicture(bytes.toByteArray(),Workbook.PICTURE_TYPE_PNG));
                    // An explicit extent also works in viewers that cannot size same-cell two-point anchors.
                    var xml=drawing.getCTDrawing();int last=xml.sizeOfTwoCellAnchorArray()-1;var two=xml.getTwoCellAnchorArray(last);
                    var one=xml.addNewOneCellAnchor();one.setFrom(two.getFrom());var extent=one.addNewExt();
                    extent.setCx((long)(thumbnail.getWidth()*factor*emu));extent.setCy((long)(thumbnail.getHeight()*factor*emu));
                    one.setPic(two.getPic());one.addNewClientData();xml.removeTwoCellAnchor(last);
                }
            }
            row++;
            String[] labels={"商品小计 / Subtotal", "折扣合计 / Discount", "税额合计 / Tax", "运费 / Freight", "其他费用 / Other fees", "应付总额 / Total ("+text(pi.getCurrencyCode())+")"};
            BigDecimal[] values={pi.getSubtotal(),pi.getDiscountAmount(),pi.getTaxAmount(),pi.getFreightAmount(),Objects.requireNonNullElse(pi.getOtherFeeAmount(),BigDecimal.ZERO),pi.getTotalAmount()};
            for(int i=0;i<labels.length;i++) {
                Row r=sheet.createRow(row++);r.setHeightInPoints(26);cell(r,0,labels[i],heading);
                sheet.addMergedRegion(new CellRangeAddress(r.getRowNum(),r.getRowNum(),0,5));amount(r,6,values[i],amount);
            }
            for (String text : terms(pi)) row = merged(sheet, row, text, body);
            merged(sheet, row, "金额按单据币种显示；超出Excel可靠精度的金额保留精确文本，单元格批注说明。总额含折扣、税额、运费及其他费用。", body);
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
        if("PI_V1".equals(pi.getDocumentRenderVersion()))return legacy.pdf(pi);
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
                    layout.item(item,thumbnail(item));
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
        lines.add("EXPORT".equals(p.getTradeType())?"EXPORT PROFORMA INVOICE / 外销形式发票":"内销形式发票 / DOMESTIC PROFORMA INVOICE");
        lines.add("PI: " + p.getPiNo() + "    Revision: " + p.getRevision() + "    " + documentState(p));
        lines.add("贸易类型: " + text(p.getTradeType()) + "    币种: " + text(p.getCurrencyCode())
                + "    计划发货: " + Objects.toString(p.getPlannedShipDate(), "未指定"));
        PiDocumentSnapshot d = p.getDocument();
        if (d != null) {
            lines.add("开单日期 / Issued: "+Objects.toString(d.issueDate(),"未记录")+"    有效期 / Valid until: "+Objects.toString(d.validUntil(),"未记录"));
            lines.add("卖方 / Seller: " + text(d.sellerName()));
            add(lines, "卖方地址: ", d.sellerAddress()); add(lines, "卖方联系方式: ", d.sellerContact());
            add(lines,"卖方国家/地区 / Seller country: ",d.sellerCountry());
        }
        lines.add("买方 / Buyer: " + text(p.getCustomerName()) + "    " + text(p.getCustomerCode()));
        if (d != null) {
            add(lines, "买方地址: ", d.buyerAddress()); add(lines, "买方联系方式: ", d.buyerContact());
            add(lines,"买方国家/地区 / Buyer country: ",d.buyerCountry());
            add(lines,"买方联系人 / Contact: ",d.buyerContactName());
            add(lines,"买方电话 / Phone: ",d.buyerPhone());
            add(lines,"买方邮箱 / Email: ",d.buyerEmail());
            add(lines,"业务归属 / Account owner: ",d.businessOwner());
        }
        return lines;
    }

    private java.awt.image.BufferedImage thumbnail(PiItemView item)throws IOException {
        if(item.getImageAttachmentId()==null)return null;
        if(images==null)throw new IOException("PI image resolver unavailable");
        var original=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(images.bytes(item)));
        if(original==null)throw new IOException("PI image decode failed");
        double ratio=Math.min(1,Math.min(320.0/original.getWidth(),240.0/original.getHeight()));
        var scaled=new java.awt.image.BufferedImage(Math.max(1,(int)(original.getWidth()*ratio)),Math.max(1,(int)(original.getHeight()*ratio)),java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=scaled.createGraphics();try {
            graphics.setColor(Color.WHITE);graphics.fillRect(0,0,scaled.getWidth(),scaled.getHeight());
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(original,0,0,scaled.getWidth(),scaled.getHeight(),null);
        } finally{graphics.dispose();}
        return scaled;
    }

    private static List<String> totals(PiView p) {
        List<String> values = new ArrayList<>(List.of("商品小计 / Subtotal: " + money(p.getSubtotal()),
                "折扣合计 / Discount: " + money(p.getDiscountAmount()) + "    税额合计 / Tax: " + money(p.getTaxAmount()),
                "运费 / Freight: " + money(p.getFreightAmount()),
                "其他费用 / Other fees: "+money(Objects.requireNonNullElse(p.getOtherFeeAmount(),BigDecimal.ZERO)),
                "应付总额 / Total (" + text(p.getCurrencyCode()) + "): " + money(p.getTotalAmount())));
        return values;
    }

    private static List<String> terms(PiView p) {
        List<String> lines = new ArrayList<>();
        PiDocumentSnapshot d = p.getDocument();
        if (d != null) {
            add(lines, "付款条款 / Payment terms: ", d.paymentTerms());
            add(lines, "交付条款 / Delivery terms: ", d.deliveryTerms());
            add(lines,"收货地址 / Ship to: ",d.shippingAddress());
            add(lines,"运输方式 / Transport: ",d.transportMode());
            if("EXPORT".equals(p.getTradeType()))add(lines,"贸易术语 / Trade terms: ",d.tradeTerms());
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
    private static void amount(Row row,int col,BigDecimal value,CellStyle style) {
        Cell cell=row.createCell(col);cell.setCellStyle(style);
        // Conservative 15 digits INCLUDING the two currency decimals, also verifying a decimal round trip.
        if(value!=null && value.setScale(2).precision()<=15
                && BigDecimal.valueOf(value.doubleValue()).setScale(2,java.math.RoundingMode.HALF_UP).compareTo(value)==0) {
            cell.setCellValue(value.doubleValue());return;
        }
        cell.setCellValue(money(value));
        if(value!=null) {
            var helper=row.getSheet().getWorkbook().getCreationHelper();var anchor=helper.createClientAnchor();
            anchor.setCol1(col);anchor.setCol2(Math.min(col+3,10));anchor.setRow1(row.getRowNum());anchor.setRow2(row.getRowNum()+3);
            var comment=row.getSheet().createDrawingPatriarch().createCellComment(anchor);
            comment.setAuthor("PI");comment.setString(helper.createRichTextString("超过Excel可靠的15位精度，保留精确十进制文本，避免金额失真。"));cell.setCellComment(comment);
        }
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
        void item(PiItemView item,java.awt.image.BufferedImage thumbnail) throws IOException {
            float textWidth=thumbnail==null?237:150,textLeft=thumbnail==null?LEFT+6:LEFT+93;
            List<String> identity = wrap(text(item.getSkuCode()) + "  " + text(item.getProductName()), 9, textWidth);
            identity.addAll(wrap(text(item.getSpecification()), 8, textWidth));
            identity.addAll(wrap("型号: "+text(item.getModelName())+"  单位: "+Objects.toString(item.getUnit(),"未记录"),8,textWidth));
            if (y - Math.min(identity.size() * 13 + 35, 80) < BOTTOM) { newPage(); tableHeader(); }
            float imageBottom=y;
            int imagePage=document.getNumberOfPages();
            if(thumbnail!=null) {
                var picture=org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document,thumbnail);
                float factor=Math.min(78f/thumbnail.getWidth(),58f/thumbnail.getHeight());
                stream.drawImage(picture,LEFT+6,y-thumbnail.getHeight()*factor,thumbnail.getWidth()*factor,thumbnail.getHeight()*factor);imageBottom=y-62;
            }
            draw(stream, font, String.valueOf(item.getQuantity()), 8, 288, y - 9);
            draw(stream, font, money(item.getUnitPrice()), 8, 342, y - 9);
            draw(stream, font, money(item.getLineTotal()), 8, 461, y - 9);
            for (String line : identity) {
                if (y - 13 < BOTTOM) { newPage(); tableHeader(); }
                draw(stream, font, line, 9, textLeft, y - 9); y -= 13;
            }
            if(thumbnail!=null&&document.getNumberOfPages()==imagePage&&y>imageBottom)y=imageBottom;
            if (y - 24 < BOTTOM) { newPage(); tableHeader(); }
            draw(stream, font, "行折扣: " + money(item.getDiscountAmount()) + "    行税额: " + money(item.getTaxAmount()), 8, LEFT + 6, y - 10);
            y -= 23; stream.setStrokingColor(PALE); stream.moveTo(LEFT, y); stream.lineTo(LEFT + WIDTH, y); stream.stroke(); y -= 7;
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }
}
