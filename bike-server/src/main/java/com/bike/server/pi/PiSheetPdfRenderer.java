package com.bike.server.pi;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide;

/**
 * 按 Excel 工作表的列宽、行高、合并区域、填充、边框、对齐、富文本粗体及图片输出 A4 PDF，
 * 使下载的 PDF 与同一份 Excel 版式一致。只读取工作表内容，不读取任何业务主数据。
 */
final class PiSheetPdfRenderer {
    /**
     * 打印区域与分页设置。行号均为 0 起始：repeatFrom/repeatTo 为每页重复的表头行（-1 不重复），
     * 仅当新页起始行不超过 repeatUntil（商品明细最后一行）时重复表头。
     */
    record Options(int firstCol, int lastCol, int lastRow, int repeatFrom, int repeatTo, int repeatUntil,
                   String title, String footerLeft, boolean pageNumbers) {}

    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth(), PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN_X = 28, MARGIN_TOP = 28, MARGIN_BOTTOM = 34, PADDING = 2.5f;
    private static final double EMU_PER_POINT = 12700d;
    /** 内容不超过一页的 1.3 倍时缩放到单页。 */
    static final float SINGLE_PAGE_TOLERANCE = 1.3f;

    private record Segment(String text, boolean bold) {}
    private record Line(List<Segment> segments) {}
    private record Placement(int row, int page, float top) {}
    private record Fonts(PDFont cjk, PDFont latin, PDFont latinBold) {}

    /** 按文字实际换行需要增加行高；Excel 与 PDF 共用调整后的行高。 */
    static void fitRows(XSSFWorkbook book, Sheet sheet, int firstCol, int lastCol) throws IOException {
        try (var document = new PDDocument(); var source = PiSheetPdfRenderer.class.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if (source == null) throw new IOException("Bundled PI font missing");
            var fonts = new Fonts(PDType0Font.load(document, source, true), new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
            var evaluator = book.getCreationHelper().createFormulaEvaluator();
            var formatter = new DataFormatter(java.util.Locale.UK);
            var merged = sheet.getMergedRegions();
            for (Row row : sheet) {
                for (Cell cell : row) {
                    int col = cell.getColumnIndex();
                    if (col < firstCol || col > lastCol) continue;
                    CellRangeAddress range = region(merged, row.getRowNum(), col);
                    if (range != null && (range.getFirstRow() != row.getRowNum() || range.getFirstColumn() != col)) continue;
                    if (range == null && columnWidth(sheet, col) <= 0) continue;
                    String value = formatter.formatCellValue(cell, evaluator);
                    if (value.isBlank()) continue;
                    var style = cell.getCellStyle();
                    if (!style.getWrapText() && !value.contains("\n")) continue;
                    int last = range == null ? col : range.getLastColumn();
                    float width = 0;
                    for (int c = col; c <= last; c++) width += columnWidth(sheet, c);
                    float size = book.getFontAt(style.getFontIndex()).getFontHeightInPoints();
                    var lines = wrap(fonts, segments(cell, value, book.getFontAt(style.getFontIndex()).getBold()), size,
                            style.getWrapText() ? width - PADDING * 2 : Float.MAX_VALUE);
                    float required = lines.size() * size * 1.25f + PADDING * 2;
                    int first = row.getRowNum(), end = range == null ? first : range.getLastRow();
                    float current = height(sheet, first, end);
                    if (required > current + .5f) {
                        float extra = (required - current) / (end - first + 1);
                        for (int index = first; index <= end; index++) {
                            Row target = sheet.getRow(index) == null ? sheet.createRow(index) : sheet.getRow(index);
                            target.setHeightInPoints(rowHeight(sheet, index) + extra);
                        }
                    }
                }
            }
        }
    }

    byte[] render(XSSFWorkbook book, Sheet sheet, Options options) throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream();
             var source = getClass().getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if (source == null) throw new IOException("Bundled PI font missing");
            var fonts = new Fonts(PDType0Font.load(document, source, true), new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
            if (options.title() != null) document.getDocumentInformation().setTitle(options.title());
            float total = 0;
            for (int col = options.firstCol(); col <= options.lastCol(); col++) total += columnWidth(sheet, col);
            float scale = Math.min(1f, (PAGE_WIDTH - MARGIN_X * 2) / Math.max(1, total));
            float[] x = new float[options.lastCol() + 2];
            x[options.firstCol()] = (PAGE_WIDTH - total * scale) / 2;
            for (int col = options.firstCol(); col <= options.lastCol(); col++) x[col + 1] = x[col] + columnWidth(sheet, col) * scale;
            var merged = sheet.getMergedRegions();
            // 内容只略超一页（例如条款多出几行）时整体缩小到一页，与 Excel 的“调整为 1 页”打印设置一致。
            float contentHeight = height(sheet, 0, options.lastRow());
            float available = PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM;
            if (contentHeight * scale > available && contentHeight * scale <= available * SINGLE_PAGE_TOLERANCE) {
                float fitted = available / contentHeight;
                float width = total * fitted;
                scale = fitted;
                x[options.firstCol()] = (PAGE_WIDTH - width) / 2;
                for (int col = options.firstCol(); col <= options.lastCol(); col++) x[col + 1] = x[col] + columnWidth(sheet, col) * scale;
            }

            // 第一遍：按行块（合并区域不跨页）计算每行所在页与纵坐标。
            var placements = new ArrayList<Placement>();
            Map<Integer, Placement> primary = new HashMap<>();
            int page = 0;
            float y = PAGE_HEIGHT - MARGIN_TOP, bottom = MARGIN_BOTTOM;
            boolean pageHasContent = false;
            for (int row = 0; row <= options.lastRow(); ) {
                int end = blockEnd(merged, row);
                float blockHeight = height(sheet, row, end) * scale;
                if (pageHasContent && y - blockHeight < bottom) {
                    page++; y = PAGE_HEIGHT - MARGIN_TOP; pageHasContent = false;
                    if (options.repeatFrom() >= 0 && row > options.repeatTo() && row <= options.repeatUntil()) {
                        for (int header = options.repeatFrom(); header <= options.repeatTo(); header++) {
                            placements.add(new Placement(header, page, y));
                            y -= rowHeight(sheet, header) * scale;
                        }
                    }
                }
                for (int index = row; index <= end; index++) {
                    var placement = new Placement(index, page, y);
                    placements.add(placement); primary.put(index, placement);
                    y -= rowHeight(sheet, index) * scale;
                    if (rowHeight(sheet, index) > 0) pageHasContent = true;
                }
                row = end + 1;
            }
            int pages = page + 1;
            var formatter = new DataFormatter(java.util.Locale.UK);
            var evaluator = book.getCreationHelper().createFormulaEvaluator();
            List<PDPageContentStream> streams = new ArrayList<>();
            try {
                for (int index = 0; index < pages; index++) {
                    var pdfPage = new PDPage(PDRectangle.A4); document.addPage(pdfPage);
                    streams.add(new PDPageContentStream(document, pdfPage));
                }
                // 第二遍：填充、文字、边框。边框最后绘制，避免被相邻单元格填充覆盖。
                for (var placement : placements) drawFills(streams.get(placement.page()), sheet, merged, placement, x, scale, options);
                for (var placement : placements) drawTexts(streams.get(placement.page()), fonts, book, sheet, merged, placement, x, scale, options, formatter, evaluator);
                for (var placement : placements) drawBorders(streams.get(placement.page()), sheet, merged, placement, x, scale, options);
                drawPictures(document, streams, sheet, primary, x, scale, options);
                for (int index = 0; index < pages; index++) {
                    var stream = streams.get(index);
                    if (options.footerLeft() != null && !options.footerLeft().isBlank())
                        text(stream, fonts, List.of(new Segment(options.footerLeft(), false)), 7.5f, MARGIN_X, 18);
                    if (options.pageNumbers() && pages > 1) {
                        String number = "Page " + (index + 1) + " / " + pages;
                        text(stream, fonts, List.of(new Segment(number, false)), 7.5f, PAGE_WIDTH - MARGIN_X - width(fonts, number, false, 7.5f), 18);
                    }
                }
            } finally {
                for (var stream : streams) stream.close();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private void drawFills(PDPageContentStream stream, Sheet sheet, List<CellRangeAddress> merged, Placement placement,
                           float[] x, float scale, Options options) throws IOException {
        var row = sheet.getRow(placement.row());
        if (row == null) return;
        for (int col = options.firstCol(); col <= options.lastCol(); col++) {
            var cell = row.getCell(col);
            if (cell == null || columnWidth(sheet, col) <= 0 || !(cell.getCellStyle() instanceof XSSFCellStyle style)) continue;
            if (style.getFillPattern() != FillPatternType.SOLID_FOREGROUND) continue;
            var color = color(style.getFillForegroundColorColor());
            if (color == null) continue;
            float top = placement.top(), h = rowHeight(sheet, placement.row()) * scale;
            stream.setNonStrokingColor(color);
            stream.addRect(x[col], top - h, x[col + 1] - x[col], h);
            stream.fill();
        }
        stream.setNonStrokingColor(Color.BLACK);
    }

    private void drawTexts(PDPageContentStream stream, Fonts fonts, XSSFWorkbook book, Sheet sheet, List<CellRangeAddress> merged,
                           Placement placement, float[] x, float scale, Options options, DataFormatter formatter,
                           FormulaEvaluator evaluator) throws IOException {
        var row = sheet.getRow(placement.row());
        if (row == null) return;
        for (int col = options.firstCol(); col <= options.lastCol(); col++) {
            var cell = row.getCell(col);
            if (cell == null) continue;
            CellRangeAddress range = region(merged, placement.row(), col);
            if (range != null && (range.getFirstRow() != placement.row() || range.getFirstColumn() != col)) continue;
            if (range == null && columnWidth(sheet, col) <= 0) continue;
            String value = formatter.formatCellValue(cell, evaluator);
            if (value.isBlank()) continue;
            var style = cell.getCellStyle();
            var font = book.getFontAt(style.getFontIndex());
            int lastCol = range == null ? col : Math.min(range.getLastColumn(), options.lastCol());
            int lastRow = range == null ? placement.row() : range.getLastRow();
            float left = x[col], right = x[lastCol + 1], top = placement.top();
            float h = height(sheet, placement.row(), lastRow) * scale;
            float size = Math.max(5.5f, font.getFontHeightInPoints() * scale);
            boolean numeric = cell.getCellType() == CellType.NUMERIC
                    || cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC;
            var lines = wrap(fonts, segments(cell, value, font.getBold()), size,
                    style.getWrapText() ? right - left - PADDING * 2 : Float.MAX_VALUE);
            float lineHeight = size * 1.25f, textHeight = lines.size() * lineHeight;
            float baseline;
            switch (style.getVerticalAlignment()) {
                case TOP -> baseline = top - PADDING - size;
                case CENTER, JUSTIFY, DISTRIBUTED -> baseline = top - (h - textHeight) / 2 - size - (lineHeight - size) / 2;
                default -> baseline = top - h + PADDING + textHeight - size - (lineHeight - size) / 2;
            }
            var alignment = style.getAlignment();
            for (var line : lines) {
                float lineWidth = 0;
                for (var segment : line.segments()) lineWidth += width(fonts, segment.text(), segment.bold(), size);
                float start = switch (alignment) {
                    case CENTER, CENTER_SELECTION -> left + (right - left - lineWidth) / 2;
                    case RIGHT -> right - PADDING - lineWidth;
                    case GENERAL -> numeric ? right - PADDING - lineWidth : left + PADDING;
                    default -> left + PADDING;
                };
                text(stream, fonts, line.segments(), size, start, baseline);
                baseline -= lineHeight;
            }
        }
    }

    private void drawBorders(PDPageContentStream stream, Sheet sheet, List<CellRangeAddress> merged, Placement placement,
                             float[] x, float scale, Options options) throws IOException {
        var styles = sheet.getWorkbook() instanceof XSSFWorkbook workbook ? workbook.getStylesSource() : null;
        var row = sheet.getRow(placement.row());
        if (row == null) return;
        float top = placement.top(), bottom = top - rowHeight(sheet, placement.row()) * scale;
        for (int col = options.firstCol(); col <= options.lastCol(); col++) {
            var cell = row.getCell(col);
            if (cell == null) continue;
            var style = cell.getCellStyle();
            CellRangeAddress range = region(merged, placement.row(), col);
            boolean topEdge = range == null || range.getFirstRow() == placement.row();
            boolean bottomEdge = range == null || range.getLastRow() == placement.row();
            boolean leftEdge = range == null || range.getFirstColumn() == col;
            boolean rightEdge = range == null || range.getLastColumn() == col;
            if (columnWidth(sheet, col) <= 0) continue;
            if (topEdge) line(stream, border(styles, style, BorderSide.TOP), x[col], top, x[col + 1], top);
            if (bottomEdge) line(stream, border(styles, style, BorderSide.BOTTOM), x[col], bottom, x[col + 1], bottom);
            if (leftEdge) line(stream, border(styles, style, BorderSide.LEFT), x[col], top, x[col], bottom);
            if (rightEdge) line(stream, border(styles, style, BorderSide.RIGHT), x[col + 1], top, x[col + 1], bottom);
        }
    }

    private void drawPictures(PDDocument document, List<PDPageContentStream> streams, Sheet sheet, Map<Integer, Placement> rows,
                              float[] x, float scale, Options options) throws IOException {
        if (!(sheet instanceof XSSFSheet xssf) || xssf.getDrawingPatriarch() == null) return;
        for (var shape : xssf.getDrawingPatriarch().getShapes()) {
            if (!(shape instanceof XSSFPicture picture) || !(picture.getAnchor() instanceof XSSFClientAnchor anchor)) continue;
            var start = rows.get(anchor.getRow1());
            if (start == null || anchor.getCol1() < options.firstCol() || anchor.getCol1() > options.lastCol()) continue;
            float left = x[anchor.getCol1()] + (float) (anchor.getDx1() / EMU_PER_POINT) * scale;
            float top = start.top() - (float) (anchor.getDy1() / EMU_PER_POINT) * scale;
            int col2 = Math.min(anchor.getCol2(), options.lastCol() + 1);
            float right = x[col2] + (float) (anchor.getDx2() / EMU_PER_POINT) * scale;
            var end = rows.get(anchor.getRow2());
            float bottomY = end != null && end.page() == start.page()
                    ? end.top() - (float) (anchor.getDy2() / EMU_PER_POINT) * scale
                    : top - (right - left) / 3;
            var data = picture.getPictureData().getData();
            var image = PDImageXObject.createFromByteArray(document, data, "picture");
            float boxW = right - left, boxH = top - bottomY;
            if (boxW <= 0 || boxH <= 0) continue;
            // 保持图片原始比例，放在锚定框内居左顶部。
            float ratio = Math.min(boxW / image.getWidth(), boxH / image.getHeight());
            float w = image.getWidth() * ratio, h = image.getHeight() * ratio;
            streams.get(start.page()).drawImage(image, left, top - h, w, h);
        }
    }

    /**
     * 直接读取单元格格式引用的边框定义。WPS 等生成的文件常省略 applyBorder 属性，
     * Excel 仍会显示边框，但 POI 的 getBorderXxx 会返回 NONE。
     */
    private static BorderStyle border(org.apache.poi.xssf.model.StylesTable styles, CellStyle style, BorderSide side) {
        if (style instanceof XSSFCellStyle xssf && styles != null) {
            var xf = xssf.getCoreXf();
            int id = xf.isSetBorderId() ? (int) xf.getBorderId() : 0;
            if (id < styles.getBorders().size()) return styles.getBorderAt(id).getBorderStyle(side);
        }
        return switch (side) {
            case TOP -> style.getBorderTop();
            case BOTTOM -> style.getBorderBottom();
            case LEFT -> style.getBorderLeft();
            default -> style.getBorderRight();
        };
    }

    private static void line(PDPageContentStream stream, BorderStyle border, float x1, float y1, float x2, float y2) throws IOException {
        if (border == null || border == BorderStyle.NONE) return;
        float width = switch (border) {
            case MEDIUM, MEDIUM_DASHED, MEDIUM_DASH_DOT, MEDIUM_DASH_DOT_DOT -> 1f;
            case THICK, DOUBLE -> 1.5f;
            case HAIR -> .25f;
            default -> .5f;
        };
        stream.setStrokingColor(Color.BLACK);
        stream.setLineWidth(width);
        stream.moveTo(x1, y1); stream.lineTo(x2, y2); stream.stroke();
    }

    private static void text(PDPageContentStream stream, Fonts fonts, List<Segment> segments, float size, float x, float y) throws IOException {
        float cursor = x;
        for (var segment : segments) {
            if (segment.text().isEmpty()) continue;
            PDFont font = font(fonts, segment.text(), segment.bold());
            boolean fakeBold = segment.bold() && font == fonts.cjk();
            stream.beginText();
            stream.setFont(font, size);
            stream.setRenderingMode(fakeBold ? RenderingMode.FILL_STROKE : RenderingMode.FILL);
            stream.setLineWidth(.25f);
            stream.newLineAtOffset(cursor, y);
            stream.showText(segment.text());
            stream.endText();
            cursor += width(fonts, segment.text(), segment.bold(), size);
        }
    }

    /** 拉丁文字使用 Helvetica 保持英文发票观感；含中文等其他字符时使用内置 Noto Sans SC。 */
    private static PDFont font(Fonts fonts, String text, boolean bold) {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch > 0xFF && ch != '€' && ch != '–' && ch != '—' && ch != '‘' && ch != '’' && ch != '“' && ch != '”' && ch != '•') return fonts.cjk();
        }
        return bold ? fonts.latinBold() : fonts.latin();
    }

    private static float width(Fonts fonts, String text, boolean bold, float size) throws IOException {
        if (text.isEmpty()) return 0;
        return font(fonts, text, bold).getStringWidth(text) / 1000 * size;
    }

    private static List<Segment> segments(Cell cell, String value, boolean bold) {
        if (cell.getCellType() == CellType.STRING && cell.getRichStringCellValue() instanceof XSSFRichTextString rich
                && rich.numFormattingRuns() > 0 && value.equals(rich.getString())) {
            var result = new ArrayList<Segment>();
            for (int run = 0; run < rich.numFormattingRuns(); run++) {
                int start = rich.getIndexOfFormattingRun(run), length = rich.getLengthOfFormattingRun(run);
                if (run == 0 && start > 0) result.add(new Segment(value.substring(0, start), bold));
                var font = rich.getFontOfFormattingRun(run);
                result.add(new Segment(value.substring(start, Math.min(value.length(), start + length)), font == null ? bold : font.getBold()));
            }
            int covered = rich.getIndexOfFormattingRun(rich.numFormattingRuns() - 1) + rich.getLengthOfFormattingRun(rich.numFormattingRuns() - 1);
            if (covered < value.length()) result.add(new Segment(value.substring(covered), bold));
            return result;
        }
        return List.of(new Segment(value, bold));
    }

    /** 按实际字宽换行，保留富文本粗体分段。 */
    private static List<Line> wrap(Fonts fonts, List<Segment> segments, float size, float width) throws IOException {
        var lines = new ArrayList<Line>();
        var current = new ArrayList<Segment>();
        var builder = new StringBuilder();
        boolean bold = false;
        float used = 0;
        for (var segment : segments) {
            if (builder.length() > 0 && bold != segment.bold()) { current.add(new Segment(builder.toString(), bold)); builder.setLength(0); }
            bold = segment.bold();
            String text = segment.text().replace("\r", "").replace('\t', ' ');
            for (int index = 0; index < text.length(); ) {
                int cp = text.codePointAt(index);
                String ch = new String(Character.toChars(cp));
                index += ch.length();
                if (cp == '\n') {
                    current.add(new Segment(builder.toString(), bold)); builder.setLength(0);
                    lines.add(new Line(List.copyOf(current))); current.clear(); used = 0; continue;
                }
                float w = width(fonts, ch, bold, size);
                if (used + w > width && (builder.length() > 0 || !current.isEmpty())) {
                    // 优先在空格处断开英文单词。
                    String pending = builder.toString();
                    int space = pending.lastIndexOf(' ');
                    if (space > 0 && cp != ' ') {
                        current.add(new Segment(pending.substring(0, space), bold));
                        lines.add(new Line(List.copyOf(current))); current.clear();
                        builder.setLength(0); builder.append(pending.substring(space + 1));
                        used = width(fonts, builder.toString(), bold, size);
                    } else {
                        current.add(new Segment(pending, bold));
                        lines.add(new Line(List.copyOf(current))); current.clear();
                        builder.setLength(0); used = 0;
                        if (cp == ' ') continue;
                    }
                }
                builder.append(ch); used += w;
            }
        }
        current.add(new Segment(builder.toString(), bold));
        lines.add(new Line(List.copyOf(current)));
        return lines;
    }

    private static Color color(XSSFColor color) {
        if (color == null) return null;
        byte[] rgb = color.getRGBWithTint();
        if (rgb == null) rgb = color.getRGB();
        if (rgb == null) return null;
        int offset = rgb.length == 4 ? 1 : 0;
        return new Color(rgb[offset] & 0xFF, rgb[offset + 1] & 0xFF, rgb[offset + 2] & 0xFF);
    }

    private static CellRangeAddress region(List<CellRangeAddress> merged, int row, int col) {
        for (var range : merged) if (range.isInRange(row, col)) return range;
        return null;
    }

    private static int blockEnd(List<CellRangeAddress> merged, int row) {
        int end = row;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var range : merged) {
                // 合并区域跨行时整体放在同一页。
                if (range.getFirstRow() >= row && range.getFirstRow() <= end && range.getLastRow() > end) {
                    end = range.getLastRow(); changed = true;
                }
            }
        }
        return end;
    }

    /** 供 Excel 打印设置使用：内容略超一页时设为“宽 1 页、高 1 页”，与 PDF 一致。 */
    static boolean nearlySinglePage(Sheet sheet, int firstCol, int lastCol, int lastRow) {
        float total = 0;
        for (int col = firstCol; col <= lastCol; col++) total += columnWidth(sheet, col);
        float scale = Math.min(1f, (PAGE_WIDTH - MARGIN_X * 2) / Math.max(1, total));
        float content = height(sheet, 0, lastRow) * scale, available = PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM;
        return content <= available * SINGLE_PAGE_TOLERANCE;
    }

    static float columnWidth(Sheet sheet, int col) {
        return sheet.isColumnHidden(col) ? 0 : sheet.getColumnWidthInPixels(col) * .75f;
    }

    static float rowHeight(Sheet sheet, int row) {
        var target = sheet.getRow(row);
        if (target == null) return sheet.getDefaultRowHeightInPoints();
        return target.getZeroHeight() ? 0 : target.getHeightInPoints();
    }

    static float height(Sheet sheet, int first, int last) {
        float value = 0;
        for (int row = first; row <= last; row++) value += rowHeight(sheet, row);
        return value;
    }
}
