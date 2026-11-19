package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 欧洲 PI 使用 France_1104 的版式，所有业务值来自本单快照。 */
final class PiEuropeDocumentRenderer {
    byte[] excel(PiView pi) {
        try (var book = workbook(pi); var output = new ByteArrayOutputStream()) {
            book.write(output);
            return output.toByteArray();
        } catch (IOException error) { throw failed(pi, error); }
    }

    byte[] pdf(PiView pi) {
        try (var book = workbook(pi)) {
            return new PiEuropeSheetPdfRenderer().render(book, pi);
        } catch (IOException | IllegalArgumentException error) { throw failed(pi, error); }
    }

    private BusinessException failed(PiView pi, Exception error) {
        LoggerFactory.getLogger(getClass()).warn("Europe PI rendering failed, piId={}", pi.getId(), error);
        return new BusinessException("PI_EXPORT_FAILED", "欧洲 PI 文件生成失败，请重试");
    }

    private XSSFWorkbook workbook(PiView pi) throws IOException {
        try (var input = getClass().getResourceAsStream("/templates/europe-pi-france.xlsx")) {
            if (input == null) throw new IOException("Europe PI layout is missing");
            var book = new XSSFWorkbook(input);
            try { populate(book, pi); return book; }
            catch (IOException | RuntimeException error) { book.close(); throw error; }
        }
    }

    private void populate(XSSFWorkbook book, PiView pi) throws IOException {
        var sheet = book.getSheetAt(0);
        book.setSheetName(0, "EUROPE PI");
        // 给百分比及客户邮箱留出宽度，避免百分号和邮箱末尾单独换行。
        sheet.setColumnWidth(7, (int)(10.5*256)); sheet.setColumnWidth(8,10*256);
        sheet.setDisplayGridlines(false); sheet.setPrintGridlines(false);
        sheet.setFitToPage(true); sheet.setHorizontallyCenter(true);
        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.getPrintSetup().setLandscape(false);
        sheet.getPrintSetup().setFitWidth((short) 1); sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.setMargin(Sheet.LeftMargin, .3); sheet.setMargin(Sheet.RightMargin, .3);
        sheet.setMargin(Sheet.TopMargin, .3); sheet.setMargin(Sheet.BottomMargin, .4);
        sheet.getPrintSetup().setFooterMargin(.15);
        for (Row row : sheet) for (Cell cell : row) {
            cell.removeHyperlink();
            var style = book.createCellStyle(); style.cloneStyleFrom(cell.getCellStyle());
            style.setWrapText(true); style.setVerticalAlignment(VerticalAlignment.TOP);
            cell.setCellStyle(style);
        }
        // 原 Excel 依靠空白相邻单元格溢出显示标题；显式合并后 PDF 与 Excel 均能完整显示。
        for(int headingRow:new int[]{6,18,24}) {
            sheet.addMergedRegion(new CellRangeAddress(headingRow,headingRow,1,4));
            sheet.addMergedRegion(new CellRangeAddress(headingRow,headingRow,7,10));
            sheet.getRow(headingRow).setHeightInPoints(21);
        }
        for(int blankRow:new int[]{5,7,19,23,25,29,30})cell(sheet,blankRow,0).getRow().setHeightInPoints(8);
        var d = pi.getDocument();
        set(sheet, "H3", text(pi.getPiNo())); fit(sheet, "H3", 32);
        if (d != null && d.issueDate() != null) {
            cell(sheet, "I4").setCellValue(d.issueDate());
            cell(sheet, "I4").getCellStyle().setDataFormat(book.createDataFormat().getFormat("dd.mm.yyyy"));
        }
        merge(sheet, 4, 7, 11, "R" + pi.getRevision() + " / " + text(pi.getStatus()), cell(sheet,"H4").getCellStyle());
        set(sheet, "B9", d == null ? "" : d.sellerName()); set(sheet, "H9", pi.getCustomerName());
        fit(sheet,"B9",35); fit(sheet,"H9",35);
        // 原模板税号、SWIFT、买方银行和发货地址没有对应快照字段，保持空白，不能从示例或主数据推断。
        set(sheet, "I10", pi.getCustomerCode());
        set(sheet, "B15", "Account:"); set(sheet,"B17","Payee:");
        set(sheet,"H17","Contact:");
        var labelFont=book.createFont();labelFont.setFontName("Arial");labelFont.setFontHeightInPoints((short)9);
        for(int labelRow=9;labelRow<=16;labelRow++)for(int col:new int[]{1,7})cell(sheet,labelRow,col).getCellStyle().setFont(labelFont);
        if (d != null) {
            set(sheet,"C12",d.sellerEmail()); set(sheet,"I12",d.buyerEmail());
            set(sheet,"C13",d.sellerPhone()); set(sheet,"I13",d.buyerPhone());
            set(sheet,"C14",d.bankName()); set(sheet,"C15",d.bankAccount());
            set(sheet,"C17",d.beneficiary()); set(sheet,"I17",join(d.buyerContactName(),d.buyerContact()));
            set(sheet,"B21",join(d.sellerName(),d.sellerAddress(),d.sellerCountry()));
            set(sheet,"H21",join(pi.getCustomerName(),d.buyerAddress(),d.buyerCountry()));
            set(sheet,"H27",d.shippingAddress());
        }
        for (String address : List.of("C12","I12","C13","I13","C14","C15","I10","C17","I17")) fit(sheet,address,28);
        for (String address : List.of("B21","H21","B27","H27")) fitMerged(sheet,address,34,3);
        if (d != null && text(d.sellerName()).toLowerCase(Locale.ROOT).startsWith("teewing ")) {
            try (var logo = getClass().getResourceAsStream("/pi/teewing-logo.png")) {
                if (logo == null) throw new IOException("PI brand image is missing");
                var anchor = book.getCreationHelper().createClientAnchor();
                anchor.setCol1(0); anchor.setRow1(0); anchor.setCol2(6); anchor.setRow2(4);
                sheet.createDrawingPatriarch().createPicture(anchor,book.addPicture(logo.readAllBytes(),Workbook.PICTURE_TYPE_PNG));
            }
        }
        var body = book.createCellStyle(); body.cloneStyleFrom(cell(sheet,"A33").getCellStyle());
        body.setWrapText(true); body.setVerticalAlignment(VerticalAlignment.TOP); body.setAlignment(HorizontalAlignment.LEFT);
        var money = book.createCellStyle(); money.cloneStyleFrom(body); money.setAlignment(HorizontalAlignment.RIGHT);
        money.setDataFormat(book.createDataFormat().getFormat("#,##0.00"));
        var percent = book.createCellStyle(); percent.cloneStyleFrom(money); percent.setDataFormat(book.createDataFormat().getFormat("0.00%"));
        var count = book.createCellStyle(); count.cloneStyleFrom(money); count.setDataFormat(book.createDataFormat().getFormat("0"));
        for(int index=sheet.getNumMergedRegions()-1;index>=0;index--)
            if(sheet.getMergedRegion(index).getFirstRow()>=32) sheet.removeMergedRegion(index);
        for(int index=sheet.getLastRowNum();index>=32;index--) { var row=sheet.getRow(index); if(row!=null)sheet.removeRow(row); }
        set(sheet,"F32","Unit Price\n("+text(pi.getCurrencyCode())+")");
        set(sheet,"I32","Price Nett\n("+text(pi.getCurrencyCode())+")");
        set(sheet,"K32","Price Gross\n("+text(pi.getCurrencyCode())+")");
        sheet.getRow(31).setHeightInPoints(34);
        int next=32;
        for (var item : pi.getItems()) {
            var description = join(item.getProductName(),item.getModelName(),item.getSpecification(),item.getSkuCode());
            if (!text(item.getUnit()).isBlank()) description += "\nUnit: " + item.getUnit();
            if (amount(item.getDiscountAmount()).signum()!=0) description += "\nDiscount: " + item.getDiscountAmount().toPlainString();
            var lines = wrap(description,36);
            for (int offset=0;offset<lines.size();offset+=14) {
                int row=next++;
                merge(sheet,row,0,3,String.join("\n",lines.subList(offset,Math.min(offset+14,lines.size()))),body);
                merge(sheet,row,5,6,"",money); merge(sheet,row,8,9,"",money); merge(sheet,row,10,11,"",money);
                sheet.getRow(row).setHeightInPoints(Math.max(26,Math.min(14,lines.size()-offset)*13+6));
                if(offset==0) {
                    numeric(sheet,row,4,BigDecimal.valueOf(item.getQuantity()),count);
                    numeric(sheet,row,5,item.getUnitPrice(),money);
                    var net=amount(item.getLineTotal()).subtract(amount(item.getTaxAmount()));
                    numeric(sheet,row,8,net,money); numeric(sheet,row,10,item.getLineTotal(),money);
                    // 系统保存行税额而非税率；此处显示税额/折后净额的有效比例，金额仍以快照为准。
                    if(net.signum()!=0) numeric(sheet,row,7,amount(item.getTaxAmount()).divide(net,10,RoundingMode.HALF_UP),percent);
                    else if(amount(item.getTaxAmount()).signum()==0) numeric(sheet,row,7,BigDecimal.ZERO,percent);
                    else merge(sheet,next++,0,11,"Line tax: "+item.getTaxAmount().toPlainString(),body);
                }
            }
        }
        var lineDiscount=pi.getItems().stream().map(i->amount(i.getDiscountAmount())).reduce(BigDecimal.ZERO,BigDecimal::add);
        var lineTax=pi.getItems().stream().map(i->amount(i.getTaxAmount())).reduce(BigDecimal.ZERO,BigDecimal::add);
        next=adjustment(sheet,next,"Order discount",amount(pi.getDiscountAmount()).subtract(lineDiscount).negate(),body,money);
        next=adjustment(sheet,next,"Order tax",amount(pi.getTaxAmount()).subtract(lineTax),body,money);
        next=adjustment(sheet,next,"Freight",amount(pi.getFreightAmount()),body,money);
        next=adjustment(sheet,next,"Other fees",amount(pi.getOtherFeeAmount()),body,money);
        merge(sheet,next,5,9,"Total Price",body); merge(sheet,next,10,11,"",money);
        numeric(sheet,next,10,pi.getTotalAmount(),money); sheet.getRow(next++).setHeightInPoints(26);
        next++;
        var terms=new ArrayList<String>();
        if(d!=null) {
            add(terms,"Payment Terms: ",d.paymentTerms()); add(terms,"Delivery Terms: ",d.deliveryTerms());
            add(terms,"Trade Terms: ",d.tradeTerms()); add(terms,"Transport: ",d.transportMode());
            add(terms,"Supplier contact: ",join(d.sellerContactName(),d.sellerContact()));
            if(d.validUntil()!=null) terms.add("Valid until: "+d.validUntil());
        }
        add(terms,"Remarks: ",pi.getRemark());
        for(String term:terms) for(String line:wrap(term,100)) {merge(sheet,next,0,11,line,body); sheet.getRow(next++).setHeightInPoints(17);}
        sheet.setRepeatingRows(CellRangeAddress.valueOf("32:32"));
        sheet.getFooter().setLeft(text(pi.getPiNo())+" / R"+pi.getRevision()); sheet.getFooter().setRight("Page &P / &N");
        book.setPrintArea(0,0,11,0,next-1);
        new PiEuropeSheetPdfRenderer().fitRows(book);
    }

    private int adjustment(Sheet sheet,int row,String label,BigDecimal amount,CellStyle body,CellStyle money) {
        if(amount.signum()==0)return row;
        merge(sheet,row,5,9,label,body); merge(sheet,row,10,11,"",money); numeric(sheet,row,10,amount,money);
        sheet.getRow(row).setHeightInPoints(18);return row+1;
    }
    private static Cell cell(Sheet sheet,String address) {var ref=new org.apache.poi.ss.util.CellReference(address);return cell(sheet,ref.getRow(),ref.getCol());}
    private static Cell cell(Sheet sheet,int row,int col) {var r=sheet.getRow(row);if(r==null)r=sheet.createRow(row);var c=r.getCell(col);return c==null?r.createCell(col):c;}
    private static void set(Sheet sheet,String address,String value) {cell(sheet,address).setCellValue(text(value));}
    private static void merge(Sheet sheet,int row,int first,int last,String value,CellStyle style) {
        if(first!=last)sheet.addMergedRegion(new CellRangeAddress(row,row,first,last));
        var c=cell(sheet,row,first);c.setCellStyle(style);c.setCellValue(text(value));
    }
    private static void numeric(Sheet sheet,int row,int col,BigDecimal value,CellStyle style) {
        var c=cell(sheet,row,col);c.setCellStyle(style);if(value==null)return;
        if(value.stripTrailingZeros().precision()>15)c.setCellValue(value.toPlainString());else c.setCellValue(value.doubleValue());
    }
    private static void fit(Sheet sheet,String address,int width) {
        var c=cell(sheet,address);c.getRow().setHeightInPoints(Math.max(c.getRow().getHeightInPoints(),wrap(c.toString(),width).size()*13+3));
    }
    private static void fitMerged(Sheet sheet,String address,int width,int rows) {
        var c=cell(sheet,address);float required=wrap(c.toString(),width).size()*13+6;
        for(int index=0;index<rows;index++)cell(sheet,c.getRowIndex()+index,0).getRow().setHeightInPoints(Math.max(14,required/rows));
    }
    static List<String> wrap(String value,int width) {
        var result=new ArrayList<String>();
        for(String line:text(value).split("\n",-1)) {
            var part=new StringBuilder();int count=0;
            for(int cp:line.codePoints().toArray()) {int weight=cp>255?2:1;if(count+weight>width){result.add(part.toString());part.setLength(0);count=0;}part.appendCodePoint(cp);count+=weight;}
            result.add(part.toString());
        }
        return result;
    }
    private static void add(List<String> values,String label,String value){if(!text(value).isBlank())values.add(label+value);}
    private static String join(String... values){return java.util.Arrays.stream(values).filter(v->v!=null&&!v.isBlank()).collect(java.util.stream.Collectors.joining("\n"));}
    private static String text(String value){return Objects.toString(value,"");}
    private static BigDecimal amount(BigDecimal value){return value==null?BigDecimal.ZERO:value;}
}
