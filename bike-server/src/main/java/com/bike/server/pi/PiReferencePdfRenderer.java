package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 客户参考发票的 PI_V5 PDF，仅使用已保存快照，不改变旧版本渲染。 */
final class PiReferencePdfRenderer {
    private static final Logger log = LoggerFactory.getLogger(PiReferencePdfRenderer.class);

    byte[] render(PiView pi, PiReferenceExcelRenderer.ProductImageLoader imageLoader) {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream();
             var source = getClass().getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if (source == null) throw new IOException("Bundled PI font is missing");
            var font = PDType0Font.load(document, source, true);
            document.getDocumentInformation().setTitle("PI " + pi.getPiNo() + " R" + pi.getRevision());
            document.getDocumentInformation().setSubject("Proforma Invoice");
            try (var layout = new Layout(document, font, pi)) {
                layout.header();
                layout.tableHeader();
                int sequence = 1;
                for (var item : pi.getItems()) layout.item(item, imageLoader.load(item), sequence++);
                layout.totals();
                layout.terms();
                layout.footers();
            }
            document.save(output);
            return output.toByteArray();
        } catch (IllegalArgumentException error) {
            log.warn("PI reference PDF unsupported character, piId={}", pi.getId(), error);
            throw new BusinessException("PI_PDF_CHARACTER_UNSUPPORTED", "PI 包含字体不支持的字符，请核对单据文字");
        } catch (IOException error) {
            log.warn("PI reference PDF rendering failed, piId={}", pi.getId(), error);
            throw new BusinessException("PI_EXPORT_FAILED", "PI PDF 生成失败，请重试");
        }
    }

    private static final class Layout implements AutoCloseable {
        private static final float LEFT = 22, RIGHT = 573, WIDTH = RIGHT - LEFT, BOTTOM = 48;
        // 与 Excel A:E 的列宽比例一致；日期占 C:D，状态位于 E。
        private static final float[] COLUMNS = {LEFT, 97, 340, 399, 479, RIGHT};
        private static final float[] METADATA_COLUMNS = {LEFT, 97, 340, 479, RIGHT};
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
            var page = new PDPage(PDRectangle.A4); document.addPage(page);
            stream = new PDPageContentStream(document, page); y = 795;
        }

        private void ensure(float height) throws IOException { if (y - height < BOTTOM) newPage(); }

        void header() throws IOException {
            var snapshot = pi.getDocument();
            if (snapshot != null && text(snapshot.sellerName()).strip().toLowerCase(Locale.ROOT).startsWith("teewing ")) {
                try (var source = getClass().getResourceAsStream("/pi/teewing-logo.png")) {
                    if (source == null) throw new IOException("PI brand resource missing");
                    var image = PDImageXObject.createFromByteArray(document, source.readAllBytes(), "teewing-logo");
                    float width = 155;
                    stream.drawImage(image, RIGHT - width, 765, width, width * image.getHeight() / image.getWidth());
                }
            }
            centered("Proforma Invoice", 17, LEFT, RIGHT, 728, true);
            var buyer = new ArrayList<String>(); var seller = new ArrayList<String>();
            buyer.add("To: " + text(pi.getCustomerName()));
            seller.add("From: " + (snapshot == null ? "" : text(snapshot.sellerName())));
            if (snapshot != null) {
                add(buyer, "", snapshot.buyerAddress()); add(buyer, "", snapshot.buyerCountry());
                add(buyer, "Contact: ", snapshot.buyerContactName()); add(buyer, "", snapshot.buyerContact());
                add(buyer, "Phone: ", snapshot.buyerPhone()); add(buyer, "Mail: ", snapshot.buyerEmail());
                add(seller, "", snapshot.sellerAddress()); add(seller, "", snapshot.sellerCountry());
                add(seller, "Contact: ", snapshot.sellerContactName()); add(seller, "", snapshot.sellerContact());
                add(seller, "Phone: ", snapshot.sellerPhone()); add(seller, "Mail: ", snapshot.sellerEmail());
            }
            var buyerLines = lines(buyer, 9, 310); var sellerLines = lines(seller, 9, RIGHT - 370);
            int buyerNameLines = wrap(buyer.getFirst(), 9, 310).size();
            int sellerNameLines = wrap(seller.getFirst(), 9, RIGHT - 370).size();
            y = 685;
            for (int index = 0; index < Math.max(buyerLines.size(), sellerLines.size()); index++) {
                ensure(13);
                if (index < buyerLines.size()) draw(buyerLines.get(index), 9, LEFT, y - 9, index < buyerNameLines);
                if (index < sellerLines.size()) draw(sellerLines.get(index), 9, 370, y - 9, index < sellerNameLines);
                y -= 13;
            }
            y = document.getNumberOfPages() == 1 ? Math.min(y - 23, 543) : y - 23;
            var labels = List.of("Order no.", "Invoice no.", "Invoice date.", "Revision / Status");
            var values = List.of(text(pi.getSourceOrderNo()), text(pi.getPiNo()),
                    snapshot == null || snapshot.issueDate() == null ? "" : snapshot.issueDate().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                    "R" + pi.getRevision() + " / " + text(pi.getStatus()));
            var valueLines = new ArrayList<List<String>>();
            int maxLines = 1;
            for (int index = 0; index < values.size(); index++) {
                var wrapped = wrap(values.get(index), 8.5f, METADATA_COLUMNS[index + 1] - METADATA_COLUMNS[index] - 7);
                valueLines.add(wrapped); maxLines = Math.max(maxLines, wrapped.size());
            }
            float height = Math.max(44, 20 + maxLines * 12);
            ensure(40 + height + 14 + 65);
            draw("I N V O I C E", 11, LEFT, y, true);
            draw(text(pi.getTradeType()) + " / " + text(pi.getCurrencyCode()), 9, LEFT, y - 15, true);
            float top = y - 40;
            stream.setNonStrokingColor(new Color(0, 176, 240));
            stream.addRect(LEFT, top - height, WIDTH, height); stream.fill();
            for (int index = 0; index < labels.size(); index++) {
                float left = METADATA_COLUMNS[index];
                draw(labels.get(index), 8.5f, left + 3, top - 10, true);
                drawLines(valueLines.get(index), left + 3, top - 23, 8.5f, 12, false);
            }
            grid(METADATA_COLUMNS, top, top - height); y = top - height - 14;
        }

        void tableHeader() throws IOException {
            ensure(65);
            String[] labels = {"Item", "Products provided", "Quantity", "Unit Price (" + text(pi.getCurrencyCode()) + ")",
                    "Total Amount\n(" + text(pi.getCurrencyCode()) + ")"};
            var headings = new ArrayList<List<String>>(); int count = 1;
            for (int index = 0; index < labels.length; index++) {
                var wrapped = wrap(labels[index], 8.5f, COLUMNS[index + 1] - COLUMNS[index] - 7);
                headings.add(wrapped); count = Math.max(count, wrapped.size());
            }
            float height = Math.max(29, count * 12 + 7);
            grid(COLUMNS, y, y - height);
            for (int index = 0; index < labels.length; index++) {
                var wrapped = headings.get(index);
                float baseline = y - (height - wrapped.size() * 12) / 2 - 9;
                for (String line : wrapped) {
                    centered(line, 8.5f, COLUMNS[index], COLUMNS[index + 1], baseline, true); baseline -= 12;
                }
            }
            y -= height;
        }

        void item(PiItemView item, BufferedImage thumbnail, int sequence) throws IOException {
            var description = new ArrayList<String>();
            add(description, "", item.getProductName()); add(description, "Model: ", item.getModelName());
            add(description, "", item.getSpecification()); add(description, "SKU: ", item.getSkuCode()); add(description, "Unit: ", item.getUnit());
            if (nonzero(item.getDiscountAmount())) add(description, "Discount: ", money(item.getDiscountAmount()));
            if (nonzero(item.getTaxAmount())) add(description, "Tax: ", money(item.getTaxAmount()));
            var detailLines = lines(description, 9, COLUMNS[2] - COLUMNS[1] - 8);
            if (detailLines.isEmpty()) detailLines.add("");
            var quantity = wrap(String.valueOf(item.getQuantity()), 9, COLUMNS[3] - COLUMNS[2] - 8);
            var unitPrice = wrap(money(item.getUnitPrice()), 9, COLUMNS[4] - COLUMNS[3] - 8);
            var lineTotal = wrap(money(item.getLineTotal()), 9, RIGHT - COLUMNS[4] - 8);
            int numericLines = Math.max(quantity.size(), Math.max(unitPrice.size(), lineTotal.size()));
            int offset = 0; boolean first = true;
            while (offset < detailLines.size()) {
                int count = Math.min(18, detailLines.size() - offset);
                float height = Math.max(35, Math.max(count, first ? numericLines : 0) * 12 + 9);
                if (first && thumbnail != null) height = Math.max(height, 75);
                if (y - height < BOTTOM) { newPage(); tableHeader(); }
                float top = y, bottom = top - height;
                grid(COLUMNS, top, bottom);
                if (first) {
                    centered(String.valueOf(sequence), 9, LEFT, COLUMNS[1], top - 14, false);
                    if (thumbnail != null) {
                        var picture = LosslessFactory.createFromImage(document, thumbnail);
                        float scale = Math.min(61f / thumbnail.getWidth(), 48f / thumbnail.getHeight());
                        stream.drawImage(picture, LEFT + 7, bottom + 6, thumbnail.getWidth() * scale, thumbnail.getHeight() * scale);
                    }
                    float baseline = top - 14;
                    for (String value : quantity) { centered(value, 9, COLUMNS[2], COLUMNS[3], baseline, false); baseline -= 12; }
                    rightLines(unitPrice, 9, COLUMNS[4] - 4, top - 14, false);
                    rightLines(lineTotal, 9, RIGHT - 4, top - 14, false);
                }
                drawLines(detailLines.subList(offset, offset + count), COLUMNS[1] + 4, top - 12, 9, 12, false);
                y = bottom; offset += count; first = false;
            }
        }

        void totals() throws IOException {
            var labels = new ArrayList<String>(); var amounts = new ArrayList<BigDecimal>();
            if (nonzero(pi.getDiscountAmount()) || nonzero(pi.getTaxAmount()) || nonzero(pi.getFreightAmount()) || nonzero(pi.getOtherFeeAmount())) {
                labels.add("Subtotal"); amounts.add(pi.getSubtotal());
                addTotal(labels, amounts, "Discount", pi.getDiscountAmount() == null ? null : pi.getDiscountAmount().negate());
                addTotal(labels, amounts, "Tax", pi.getTaxAmount());
                addTotal(labels, amounts, "Freight", pi.getFreightAmount());
                addTotal(labels, amounts, "Other fees", pi.getOtherFeeAmount());
            }
            labels.add("Net total"); amounts.add(pi.getTotalAmount());
            if (y - labels.size() * 22 < BOTTOM) { newPage(); tableHeader(); }
            for (int index = 0; index < labels.size(); index++) {
                var values = wrap(money(amounts.get(index)), 9, RIGHT - COLUMNS[4] - 8);
                float height = Math.max(22, values.size() * 12 + 8);
                if (y - height < BOTTOM) { newPage(); tableHeader(); }
                grid(new float[]{LEFT, COLUMNS[4], RIGHT}, y, y - height);
                draw(labels.get(index), 9, LEFT + 3, y - 14, true);
                rightLines(values, 9, RIGHT - 4, y - 14, true); y -= height;
            }
        }

        void terms() throws IOException {
            y -= 5;
            var snapshot = pi.getDocument();
            if (snapshot != null) {
                var terms = new ArrayList<String>();
                if ("EXPORT".equals(pi.getTradeType())) add(terms, "Trade Term: ", snapshot.tradeTerms());
                add(terms, "Shipping: ", snapshot.deliveryTerms()); add(terms, "Payment Terms: ", snapshot.paymentTerms());
                add(terms, "Ship to: ", snapshot.shippingAddress()); add(terms, "Transport: ", snapshot.transportMode());
                if (snapshot.validUntil() != null) terms.add("Valid until: " + snapshot.validUntil());
                if (pi.getPlannedShipDate() != null) terms.add("Planned shipment: " + pi.getPlannedShipDate());
                for (String term : terms) paragraph(term, COLUMNS[1], false);
                y -= 18;
                optionalParagraph("Account Name: ", snapshot.beneficiary(), true);
                optionalParagraph("Company Address: ", snapshot.sellerAddress(), false);
                optionalParagraph("Account Number: ", snapshot.bankAccount(), false);
                optionalParagraph("Bank Name: ", snapshot.bankName(), false);
            }
            optionalParagraph("Remarks: ", pi.getRemark(), false);
        }

        private void optionalParagraph(String label, String value, boolean bold) throws IOException {
            if (value != null && !value.isBlank()) paragraph(label + value, LEFT, bold);
        }

        private void paragraph(String value, float left, boolean bold) throws IOException {
            var wrapped = wrap(value, 9, RIGHT - left - 3);
            float height = wrapped.size() * 13 + 5;
            if (height < 740) ensure(height);
            for (String line : wrapped) { ensure(13); draw(line, 9, left, y - 9, bold); y -= 13; }
            y -= 5;
        }

        void footers() throws IOException {
            stream.close(); stream = null;
            int count = document.getNumberOfPages();
            for (int index = 0; index < count; index++) {
                try (var footer = new PDPageContentStream(document, document.getPage(index), PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream = footer;
                    draw(text(pi.getPiNo()) + " / R" + pi.getRevision(), 7, LEFT, 20, false);
                    String page = "Page " + (index + 1) + " / " + count;
                    draw(page, 7, RIGHT - width(page, 7), 20, false);
                } finally { stream = null; }
            }
        }

        private void grid(float[] columns, float top, float bottom) throws IOException {
            stream.setStrokingColor(Color.BLACK); stream.setLineWidth(0.6f);
            for (float column : columns) { stream.moveTo(column, top); stream.lineTo(column, bottom); }
            stream.moveTo(LEFT, top); stream.lineTo(RIGHT, top);
            stream.moveTo(LEFT, bottom); stream.lineTo(RIGHT, bottom); stream.stroke();
        }

        private void draw(String value, float size, float x, float baseline, boolean bold) throws IOException {
            stream.saveGraphicsState(); stream.setNonStrokingColor(Color.BLACK);
            // 内嵌中文字体以细描边加粗，无需依赖服务器本地字体。
            if (bold) { stream.setStrokingColor(Color.BLACK); stream.setLineWidth(0.22f); }
            stream.beginText(); stream.setFont(font, size);
            stream.setRenderingMode(bold ? RenderingMode.FILL_STROKE : RenderingMode.FILL);
            stream.newLineAtOffset(x, baseline); stream.showText(value); stream.endText(); stream.restoreGraphicsState();
        }

        private void centered(String value, float size, float left, float right, float baseline, boolean bold) throws IOException {
            draw(value, size, left + (right - left - width(value, size)) / 2, baseline, bold);
        }

        private void drawLines(List<String> lines, float left, float baseline, float size, float leading, boolean bold) throws IOException {
            for (String line : lines) { draw(line, size, left, baseline, bold); baseline -= leading; }
        }

        private void rightLines(List<String> lines, float size, float right, float baseline, boolean bold) throws IOException {
            for (String line : lines) { draw(line, size, right - width(line, size), baseline, bold); baseline -= 12; }
        }

        private float width(String value, float size) throws IOException { return font.getStringWidth(value) / 1000 * size; }

        private List<String> lines(List<String> values, float size, float width) throws IOException {
            var result = new ArrayList<String>();
            for (String value : values) result.addAll(wrap(value, size, width));
            return result;
        }

        private List<String> wrap(String value, float size, float maxWidth) throws IOException {
            var lines = new ArrayList<String>();
            for (String paragraph : text(value).replace('\t', ' ').split("\\R", -1)) {
                var current = new StringBuilder();
                for (int point : paragraph.codePoints().toArray()) {
                    if (Character.isISOControl(point)) continue;
                    String glyph = new String(Character.toChars(point));
                    if (!current.isEmpty() && width(current + glyph, size) > maxWidth) {
                        int space = current.lastIndexOf(" ");
                        if (space > current.length() / 2) {
                            lines.add(current.substring(0, space)); current.delete(0, space + 1);
                        } else { lines.add(current.toString()); current.setLength(0); }
                    }
                    if (!current.isEmpty() || !glyph.equals(" ")) current.append(glyph);
                }
                lines.add(current.toString());
            }
            return lines;
        }

        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }

    private static void addTotal(List<String> labels, List<BigDecimal> amounts, String label, BigDecimal value) {
        if (nonzero(value)) { labels.add(label); amounts.add(value); }
    }
    private static void add(List<String> lines, String label, String value) { if (value != null && !value.isBlank()) lines.add(label + value); }
    private static boolean nonzero(BigDecimal value) { return value != null && value.signum() != 0; }
    private static String text(String value) { return value == null ? "" : value; }
    private static String money(BigDecimal value) { return value == null ? "" : value.toPlainString(); }
}
