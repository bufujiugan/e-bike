package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 按参考 PI 排版，仅使用单据快照；品牌图与商品图之外不嵌入签名图片。 */
final class PiReferenceExcelRenderer {
    private static final Logger log = LoggerFactory.getLogger(PiReferenceExcelRenderer.class);
    private static final int TABLE_HEADER_ROW = 16;
    private static final int PRODUCT_LINE_WIDTH = 45;

    @FunctionalInterface
    interface ProductImageLoader {
        BufferedImage load(PiItemView item) throws IOException;
    }

    byte[] render(PiView pi, ProductImageLoader imageLoader) {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("INVOICE");
            var styles = new InvoiceStyles(book);
            configureSheet(sheet);
            int tableHeaderRow = renderHeader(sheet, pi, styles);
            renderBrand(sheet, pi);
            int nextRow = renderItems(sheet, pi, tableHeaderRow, styles, imageLoader);
            nextRow = renderTotals(sheet, pi, nextRow, styles);
            nextRow = renderTerms(sheet, pi, nextRow, styles);
            sheet.setRepeatingRows(new CellRangeAddress(tableHeaderRow, tableHeaderRow, -1, -1));
            sheet.getFooter().setLeft(text(pi.getPiNo()) + " / R" + pi.getRevision());
            sheet.getFooter().setRight("Page &P / &N");
            book.setPrintArea(0, 0, 4, 0, nextRow - 1);
            book.write(output);
            return output.toByteArray();
        } catch (IOException error) {
            log.warn("PI reference Excel rendering failed, piId={}", pi.getId(), error);
            throw new BusinessException("PI_EXPORT_FAILED", "PI Excel 生成失败，请重试");
        }
    }

    private void configureSheet(Sheet sheet) {
        double[] widths = {15, 48.9, 11.92, 16, 19};
        for (int col = 0; col < widths.length; col++) sheet.setColumnWidth(col, (int) (widths[col] * 256));
        sheet.setDefaultRowHeightInPoints(16.5f);
        sheet.setDisplayGridlines(false);
        sheet.setPrintGridlines(false);
        sheet.setFitToPage(true);
        sheet.setHorizontallyCenter(true);
        sheet.setMargin(Sheet.LeftMargin, 0.3);
        sheet.setMargin(Sheet.RightMargin, 0.3);
        sheet.setMargin(Sheet.TopMargin, 0.35);
        sheet.setMargin(Sheet.BottomMargin, 0.35);
        // 页脚与正文下边距留出足够高度，避免打印时单号和页码被裁掉。
        sheet.getPrintSetup().setFooterMargin(0.15);
        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.getPrintSetup().setLandscape(false);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
    }

    private int renderHeader(Sheet sheet, PiView pi, InvoiceStyles styles) {
        row(sheet, 1).setHeightInPoints(49.5f);
        merge(sheet, 2, 1, 3, "Proforma Invoice", styles.title);
        row(sheet, 2).setHeightInPoints(49.5f);
        PiDocumentSnapshot document = pi.getDocument();
        merge(sheet, 5, 0, 1, "To: " + text(pi.getCustomerName()), styles.bold);
        write(sheet, 5, 2, "From:", styles.rightBold);
        merge(sheet, 5, 3, 4, document == null ? "" : document.sellerName(), styles.bold);
        List<String> buyer = new ArrayList<>(), seller = new ArrayList<>();
        if (document != null) {
            add(buyer, "", document.buyerAddress());
            add(buyer, "", document.buyerCountry());
            add(buyer, "Contact: ", document.buyerContactName());
            add(buyer, "", document.buyerContact());
            add(buyer, "Phone: ", document.buyerPhone());
            add(buyer, "Mail: ", document.buyerEmail());
            add(seller, "", document.sellerAddress());
            add(seller, "", document.sellerCountry());
            add(seller, "Contact: ", document.sellerContactName());
            add(seller, "", document.sellerContact());
            add(seller, "Phone: ", document.sellerPhone());
            add(seller, "Mail: ", document.sellerEmail());
        }
        // 地址分行展示，超过三组时后移明细表，防止截断资料或超过单行高度上限。
        List<String> buyerLines = wrap(String.join("\n", buyer), 60);
        List<String> sellerLines = wrap(String.join("\n", seller), 32);
        int partyRows = Math.max(3, (Math.max(buyerLines.size(), sellerLines.size()) + 17) / 18);
        for (int part = 0; part < partyRows; part++) {
            String left = chunk(buyerLines, part, 18), right = chunk(sellerLines, part, 18);
            merge(sheet, 6 + part, 0, 1, left, styles.body);
            merge(sheet, 6 + part, 3, 4, right, styles.body);
            row(sheet, 6 + part).setHeightInPoints(left.isEmpty() && right.isEmpty() ? 12 :
                    Math.max(part == 0 ? 89.25f : 18, 15 * Math.max(lineCount(left), lineCount(right)) + 6));
        }
        int shift = partyRows - 3;
        merge(sheet, 10 + shift, 0, 4, "I N V O I C E", styles.section);
        merge(sheet, 11 + shift, 0, 4, text(pi.getTradeType()) + " / " + text(pi.getCurrencyCode()), styles.bold);
        int metadataRow = 13 + shift, valuesRow = metadataRow + 1;
        write(sheet, metadataRow, 0, "Order no.", styles.metadata);
        write(sheet, metadataRow, 1, "Invoice no.", styles.metadata);
        merge(sheet, metadataRow, 2, 3, "Invoice date.", styles.metadata);
        write(sheet, metadataRow, 4, "Revision / Status", styles.metadata);
        // PI 无来源订单时保留空白，绝不以数据库主键代替业务单号。
        boolean currentLayout = "PI_V5".equals(pi.getDocumentRenderVersion());
        String orderNo = currentLayout ? text(pi.getSourceOrderNo()) : "";
        write(sheet, valuesRow, 0, orderNo, styles.metadataValue);
        write(sheet, valuesRow, 1, pi.getPiNo(), styles.metadataValue);
        merge(sheet, valuesRow, 2, 3, "", styles.date);
        if (document != null && document.issueDate() != null)
            row(sheet, valuesRow).getCell(2).setCellValue(document.issueDate());
        write(sheet, valuesRow, 4, "R" + pi.getRevision() + " / " + text(pi.getStatus()), styles.metadataValue);
        row(sheet, valuesRow).setHeightInPoints(32);
        if (currentLayout) {
            int valueLines = Math.max(wrap(orderNo, 14).size(),
                    Math.max(wrap(text(pi.getPiNo()), 47).size(), wrap("R" + pi.getRevision() + " / " + text(pi.getStatus()), 18).size()));
            row(sheet, valuesRow).setHeightInPoints(Math.max(32, valueLines * 15 + 6));
        }
        row(sheet, 5).setHeightInPoints(15 * Math.max(wrap("To: " + text(pi.getCustomerName()), 60).size(),
                wrap(document == null ? "" : document.sellerName(), 32).size()) + 6);
        return TABLE_HEADER_ROW + shift;
    }

    private void renderBrand(Sheet sheet, PiView pi) throws IOException {
        var document = pi.getDocument();
        if (document == null || !text(document.sellerName()).strip().toLowerCase(Locale.ROOT).startsWith("teewing ")) return;
        try (var source = getClass().getResourceAsStream("/pi/teewing-logo.png")) {
            if (source == null) throw new IOException("PI brand resource missing");
            addImage(sheet, source.readAllBytes(), 1, 3, 10, 1, 250, 63);
        }
    }

    private int renderItems(Sheet sheet, PiView pi, int tableHeaderRow, InvoiceStyles styles, ProductImageLoader imageLoader) throws IOException {
        String currency = text(pi.getCurrencyCode());
        String[] headings = {"Item", "Products provided", "Quantity", "Unit Price (" + currency + ")", "Total Amount (" + currency + ")"};
        for (int col = 0; col < headings.length; col++) write(sheet, tableHeaderRow, col, headings[col], styles.tableHeading);
        row(sheet, tableHeaderRow).setHeightInPoints(32);
        int nextRow = tableHeaderRow + 1, sequence = 1;
        for (PiItemView item : pi.getItems()) {
            List<String> description = new ArrayList<>();
            add(description, "", item.getProductName());
            add(description, "Model: ", item.getModelName());
            add(description, "", item.getSpecification());
            add(description, "SKU: ", item.getSkuCode());
            add(description, "Unit: ", item.getUnit());
            if (nonzero(item.getDiscountAmount())) description.add("Discount: " + item.getDiscountAmount().toPlainString());
            if (nonzero(item.getTaxAmount())) description.add("Tax: " + item.getTaxAmount().toPlainString());
            List<String> lines = wrap(String.join("\n", description), PRODUCT_LINE_WIDTH);
            BufferedImage productImage = imageLoader.load(item);
            for (int offset = 0; offset < Math.max(1, lines.size()); offset += 18) {
                int rowIndex = nextRow++;
                for (int col = 0; col < 5; col++) write(sheet, rowIndex, col, "", styles.tableBody);
                String part = String.join("\n", lines.subList(offset, Math.min(offset + 18, lines.size())));
                write(sheet, rowIndex, 1, part, styles.product);
                row(sheet, rowIndex).setHeightInPoints(Math.max(25, 15 * lineCount(part) + 10));
                if (offset == 0) {
                    row(sheet, rowIndex).getCell(0).setCellValue(sequence);
                    row(sheet, rowIndex).getCell(2).setCellValue(item.getQuantity());
                    amount(sheet, rowIndex, 3, item.getUnitPrice(), styles.money);
                    amount(sheet, rowIndex, 4, item.getLineTotal(), styles.money);
                    if (productImage != null) {
                        row(sheet, rowIndex).getCell(0).setCellStyle(styles.imageItem);
                        row(sheet, rowIndex).setHeightInPoints(Math.max(90, row(sheet, rowIndex).getHeightInPoints()));
                        double scale = Math.min(94.0 / productImage.getWidth(), 72.0 / productImage.getHeight());
                        var imageBytes = new ByteArrayOutputStream();
                        ImageIO.write(productImage, "png", imageBytes);
                        addImage(sheet, imageBytes.toByteArray(), rowIndex, 0, 5, 25,
                                (int) (productImage.getWidth() * scale), (int) (productImage.getHeight() * scale));
                    }
                }
            }
            sequence++;
        }
        return nextRow;
    }

    private int renderTotals(Sheet sheet, PiView pi, int nextRow, InvoiceStyles styles) {
        boolean adjusted = nonzero(pi.getDiscountAmount()) || nonzero(pi.getTaxAmount())
                || nonzero(pi.getFreightAmount()) || nonzero(pi.getOtherFeeAmount());
        if (adjusted) {
            nextRow = total(sheet, nextRow, "Subtotal", pi.getSubtotal(), styles);
            if (nonzero(pi.getDiscountAmount())) nextRow = total(sheet, nextRow, "Discount", pi.getDiscountAmount().negate(), styles);
            if (nonzero(pi.getTaxAmount())) nextRow = total(sheet, nextRow, "Tax", pi.getTaxAmount(), styles);
            if (nonzero(pi.getFreightAmount())) nextRow = total(sheet, nextRow, "Freight", pi.getFreightAmount(), styles);
            if (nonzero(pi.getOtherFeeAmount())) nextRow = total(sheet, nextRow, "Other fees", pi.getOtherFeeAmount(), styles);
        }
        // 正式 PI 以保存的金额为准，不能因打开 Excel 重算而改变已确认价格。
        return total(sheet, nextRow, "Net total", pi.getTotalAmount(), styles);
    }

    private int total(Sheet sheet, int rowIndex, String label, BigDecimal value, InvoiceStyles styles) {
        merge(sheet, rowIndex, 0, 3, label, styles.totalLabel);
        amount(sheet, rowIndex, 4, value, styles.totalMoney);
        row(sheet, rowIndex).setHeightInPoints(24);
        return rowIndex + 1;
    }

    private int renderTerms(Sheet sheet, PiView pi, int nextRow, InvoiceStyles styles) {
        var document = pi.getDocument();
        if (document != null) {
            List<String> terms = new ArrayList<>();
            if ("EXPORT".equals(pi.getTradeType())) add(terms, "Trade Term: ", document.tradeTerms());
            add(terms, "Shipping: ", document.deliveryTerms());
            add(terms, "Payment Terms: ", document.paymentTerms());
            add(terms, "Ship to: ", document.shippingAddress());
            add(terms, "Transport: ", document.transportMode());
            if (document.validUntil() != null) terms.add("Valid until: " + document.validUntil());
            if (pi.getPlannedShipDate() != null) terms.add("Planned shipment: " + pi.getPlannedShipDate());
            for (String term : terms) nextRow = paragraph(sheet, nextRow, 1, term, styles.body);
            nextRow++;
            nextRow = optionalParagraph(sheet, nextRow, "Account Name: ", document.beneficiary(), styles.bold);
            nextRow = optionalParagraph(sheet, nextRow, "Company Address: ", document.sellerAddress(), styles.body);
            nextRow = optionalParagraph(sheet, nextRow, "Account Number: ", document.bankAccount(), styles.body);
            nextRow = optionalParagraph(sheet, nextRow, "Bank Name: ", document.bankName(), styles.body);
        }
        return optionalParagraph(sheet, nextRow, "Remarks: ", pi.getRemark(), styles.body);
    }

    private int optionalParagraph(Sheet sheet, int nextRow, String label, String value, CellStyle style) {
        return value == null || value.isBlank() ? nextRow : paragraph(sheet, nextRow, 0, label + value, style);
    }

    private int paragraph(Sheet sheet, int nextRow, int firstCol, String value, CellStyle style) {
        List<String> lines = wrap(value, firstCol == 0 ? 105 : 90);
        for (int offset = 0; offset < lines.size(); offset += 8) {
            String part = String.join("\n", lines.subList(offset, Math.min(offset + 8, lines.size())));
            merge(sheet, nextRow, firstCol, 4, part, style);
            row(sheet, nextRow++).setHeightInPoints(lineCount(part) * 15 + 6);
        }
        return nextRow;
    }

    private static void addImage(Sheet sheet, byte[] bytes, int rowIndex, int column, int x, int y, int width, int height) {
        var book = sheet.getWorkbook();
        var anchor = book.getCreationHelper().createClientAnchor();
        anchor.setCol1(column); anchor.setCol2(column); anchor.setRow1(rowIndex); anchor.setRow2(rowIndex);
        anchor.setDx1(x * Units.EMU_PER_PIXEL); anchor.setDy1(y * Units.EMU_PER_PIXEL);
        anchor.setDx2((x + width) * Units.EMU_PER_PIXEL); anchor.setDy2((y + height) * Units.EMU_PER_PIXEL);
        var drawing = (XSSFDrawing) sheet.createDrawingPatriarch();
        drawing.createPicture(anchor, book.addPicture(bytes, Workbook.PICTURE_TYPE_PNG));
        var xml = drawing.getCTDrawing();
        int last = xml.sizeOfTwoCellAnchorArray() - 1;
        var twoCell = xml.getTwoCellAnchorArray(last);
        var oneCell = xml.addNewOneCellAnchor();
        oneCell.setFrom(twoCell.getFrom());
        var extent = oneCell.addNewExt();
        extent.setCx((long) width * Units.EMU_PER_PIXEL); extent.setCy((long) height * Units.EMU_PER_PIXEL);
        oneCell.setPic(twoCell.getPic()); oneCell.addNewClientData();
        xml.removeTwoCellAnchor(last);
    }

    private static void amount(Sheet sheet, int rowIndex, int column, BigDecimal value, CellStyle style) {
        Cell cell = write(sheet, rowIndex, column, "", style);
        if (value == null) return;
        if (value.setScale(2).precision() <= 15
                && BigDecimal.valueOf(value.doubleValue()).setScale(2, RoundingMode.HALF_UP).compareTo(value) == 0) {
            cell.setCellValue(value.doubleValue());
        } else {
            // 与历史导出一致：超过 Excel 精度时保留十进制文本及说明。
            cell.setCellValue(value.toPlainString());
            var helper = sheet.getWorkbook().getCreationHelper();
            var anchor = helper.createClientAnchor();
            anchor.setCol1(column); anchor.setCol2(column + 3); anchor.setRow1(rowIndex); anchor.setRow2(rowIndex + 3);
            var comment = sheet.createDrawingPatriarch().createCellComment(anchor);
            comment.setAuthor("PI");
            comment.setString(helper.createRichTextString("超过Excel可靠的15位精度，保留精确十进制文本，避免金额失真。"));
            cell.setCellComment(comment);
        }
    }

    private static Row row(Sheet sheet, int index) {
        Row result = sheet.getRow(index);
        return result == null ? sheet.createRow(index) : result;
    }

    private static Cell write(Sheet sheet, int rowIndex, int column, String value, CellStyle style) {
        Row row = row(sheet, rowIndex);
        Cell cell = row.getCell(column);
        if (cell == null) cell = row.createCell(column);
        if (value == null || value.isEmpty()) cell.setBlank();
        else cell.setCellValue(value);
        cell.setCellStyle(style);
        return cell;
    }

    private static void merge(Sheet sheet, int rowIndex, int start, int end, String value, CellStyle style) {
        for (int col = start; col <= end; col++) write(sheet, rowIndex, col, col == start ? value : "", style);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, start, end));
    }

    private static List<String> wrap(String value, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text(value).split("\\R", -1)) {
            StringBuilder line = new StringBuilder();
            int used = 0;
            for (int codePoint : paragraph.codePoints().toArray()) {
                int size = codePoint > 255 ? 2 : 1;
                if (used + size > width) {
                    int space = line.lastIndexOf(" ");
                    if (space > line.length() / 2) {
                        lines.add(line.substring(0, space));
                        line.delete(0, space + 1);
                        used = line.codePoints().map(point -> point > 255 ? 2 : 1).sum();
                    } else {
                        lines.add(line.toString()); line.setLength(0); used = 0;
                    }
                }
                line.appendCodePoint(codePoint); used += size;
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static String chunk(List<String> lines, int part, int size) {
        int start = part * size;
        return start >= lines.size() ? "" : String.join("\n", lines.subList(start, Math.min(start + size, lines.size())));
    }

    private static int lineCount(String value) { return value.split("\\R", -1).length; }
    private static boolean nonzero(BigDecimal value) { return value != null && value.signum() != 0; }
    private static String text(String value) { return value == null ? "" : value; }
    private static void add(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) lines.add(label + value);
    }

    private static final class InvoiceStyles {
        final CellStyle body, bold, rightBold, title, section, metadata, metadataValue, date;
        final CellStyle tableHeading, tableBody, product, money, totalLabel, totalMoney, imageItem;

        InvoiceStyles(Workbook book) {
            Font regular = font(book, 10, false), strong = font(book, 10, true);
            body = book.createCellStyle();
            body.setFont(regular); body.setWrapText(true); body.setVerticalAlignment(VerticalAlignment.TOP);
            bold = copy(book, body); bold.setFont(strong);
            rightBold = copy(book, bold); rightBold.setAlignment(HorizontalAlignment.RIGHT);
            title = copy(book, bold); title.setFont(font(book, 18, true));
            title.setAlignment(HorizontalAlignment.CENTER); title.setVerticalAlignment(VerticalAlignment.CENTER);
            section = copy(book, bold); section.setFont(font(book, 12, true));
            metadata = copy(book, bold);
            ((org.apache.poi.xssf.usermodel.XSSFCellStyle) metadata).setFillForegroundColor(
                    new org.apache.poi.xssf.usermodel.XSSFColor(new byte[]{0, (byte) 176, (byte) 240}, null));
            metadata.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            metadata.setBorderTop(BorderStyle.THIN); metadata.setBorderLeft(BorderStyle.THIN); metadata.setBorderRight(BorderStyle.THIN);
            metadataValue = copy(book, metadata); metadataValue.setFont(regular);
            metadataValue.setBorderTop(BorderStyle.NONE); metadataValue.setBorderBottom(BorderStyle.THIN);
            date = copy(book, metadataValue); date.setDataFormat(book.createDataFormat().getFormat("dd.mm.yyyy"));
            date.setAlignment(HorizontalAlignment.LEFT);
            tableBody = copy(book, body); tableBody.setAlignment(HorizontalAlignment.CENTER);
            tableBody.setVerticalAlignment(VerticalAlignment.CENTER);
            tableBody.setBorderTop(BorderStyle.THIN); tableBody.setBorderBottom(BorderStyle.THIN);
            tableBody.setBorderLeft(BorderStyle.THIN); tableBody.setBorderRight(BorderStyle.THIN);
            tableHeading = copy(book, tableBody); tableHeading.setFont(strong);
            product = copy(book, tableBody); product.setAlignment(HorizontalAlignment.LEFT);
            money = copy(book, tableBody); money.setAlignment(HorizontalAlignment.RIGHT);
            money.setDataFormat(book.createDataFormat().getFormat("#,##0.00"));
            totalLabel = copy(book, tableBody); totalLabel.setFont(strong); totalLabel.setAlignment(HorizontalAlignment.LEFT);
            totalMoney = copy(book, money); totalMoney.setFont(strong);
            imageItem = copy(book, tableBody); imageItem.setVerticalAlignment(VerticalAlignment.TOP);
        }

        private static CellStyle copy(Workbook book, CellStyle source) {
            CellStyle result = book.createCellStyle(); result.cloneStyleFrom(source); return result;
        }

        private static Font font(Workbook book, int size, boolean bold) {
            Font font = book.createFont(); font.setFontName("Microsoft YaHei");
            font.setFontHeightInPoints((short) size); font.setBold(bold); return font;
        }
    }
}
