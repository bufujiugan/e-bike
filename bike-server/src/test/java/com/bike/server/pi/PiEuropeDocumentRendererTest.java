package com.bike.server.pi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PiEuropeDocumentRendererTest {
    private final PiDocumentService documents=new PiDocumentService();
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();

    @Test void bothEuropeanTradeTypesSelectSameLayoutAndFreezeItInArchive() throws Exception {
        var templates=mock(PiTemplateMapper.class);
        var template=new PiTemplate(1,"GENERIC-PREVIEW","1","EXPORT","EXPORT_V3","Seller","Address","Contact","Bank","Account","Payee","Reason",1L,null,"NL","Sales","Phone","sales@example.invalid");
        when(templates.find("GENERIC-PREVIEW","1")).thenReturn(template);
        var selector=new PiTemplateService(templates,null,null,null);
        for(String trade:new String[]{"DOMESTIC","EXPORT"}) {
            var pi=sample(1);pi.setTradeType(trade);selector.selectRenderer(pi);
            // 新草稿与新归档使用修正后的 France 版式；已入队的 PI_EUROPE_V1 仍按原版本生成。
            assertThat(pi.getDocumentRenderVersion()).isEqualTo(PiEuropeDocumentRendererV2.VERSION);
            var pis=mock(PiMapper.class);when(pis.selectItems(pi.getId())).thenReturn(pi.getItems());
            var archive=new PiExportArchive(null,pis,null,json,null);ReflectionTestUtils.setField(archive,"templates",templates);
            var saved=archive.publicSnapshot(pi);
            assertThat(saved.getDocumentRenderVersion()).isEqualTo(PiEuropeDocumentRendererV2.VERSION);
            assertThat(saved.getDocument().bankAccount()).isEqualTo("SAMPLE-ACCOUNT");
        }
        var ordinary=PiDocumentServiceTest.sample(1);selector.selectRenderer(ordinary);
        assertThat(ordinary.getDocumentRenderVersion()).isEqualTo("PI_V5");
        var historical=sample(1);historical.setDocumentRenderVersion("PI_V5");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(documents.excel(historical)))) {
            assertThat(book.getSheetName(0)).isEqualTo("INVOICE");
        }
    }

    @Test void templatePreservesLayoutUsesSavedPartiesAndReconcilesTaxDiscountAndFees() throws Exception {
        var pi=sample(1);
        byte[] bytes=documents.excel(pi);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getMergedRegions()).contains(CellRangeAddress.valueOf("B21:E23"),CellRangeAddress.valueOf("H27:K29"),CellRangeAddress.valueOf("A33:D33"));
            assertThat(sheet.getRow(8).getCell(1).getStringCellValue()).isEqualTo("Teewing SAMPLE Seller");
            assertThat(sheet.getRow(14).getCell(2).getStringCellValue()).isEqualTo("SAMPLE-ACCOUNT");
            assertThat(sheet.getRow(10).getCell(2).toString()).isEmpty();
            assertThat(sheet.getRow(26).getCell(1).toString()).isEmpty();
            assertThat(sheet.getRow(32).getCell(4).getNumericCellValue()).isEqualTo(3);
            assertThat(sheet.getRow(32).getCell(5).getNumericCellValue()).isEqualTo(100);
            assertThat(sheet.getRow(32).getCell(7).getNumericCellValue()).isEqualTo(.21);
            assertThat(sheet.getRow(32).getCell(8).getNumericCellValue()).isEqualTo(270);
            assertThat(sheet.getRow(32).getCell(10).getNumericCellValue()).isEqualTo(326.7);
            assertThat(sheet.getRow(37).getCell(10).getNumericCellValue()).isEqualTo(336.7);
            assertThat(sheet.getRepeatingRows()).isEqualTo(CellRangeAddress.valueOf("32:32"));
            assertThat(content(book)).contains("SAMPLE-BUYER","Price Nett","Price Gross","EUR","Sample delivery address")
                    .doesNotContain("RACE COMPANY","TOTEMBIKES","NL868411541B01","eusales@","2999");
            sheet.forEach(row->row.forEach(cell->{assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);assertThat(cell.getHyperlink()).isNull();}));
        }
        String folder=System.getProperty("bike.pi.europe.preview.dir");
        if(folder!=null) {
            var path=Path.of(folder);Files.createDirectories(path);Files.write(path.resolve("europe-pi-preview.xlsx"),bytes);
            byte[] pdfBytes=documents.pdf(pi);Files.write(path.resolve("europe-pi-preview.pdf"),pdfBytes);
            try(var pdf=Loader.loadPDF(pdfBytes)) {var renderer=new PDFRenderer(pdf);for(int page=0;page<pdf.getNumberOfPages();page++)ImageIO.write(renderer.renderImageWithDPI(page,120),"PNG",path.resolve("europe-pi-preview-"+(page+1)+".png").toFile());}
        }
    }

    @Test void pdfContainsAllItemsAndSavedTermsAcrossPages() throws Exception {
        var pi=sample(50);
        try(var pdf=Loader.loadPDF(documents.pdf(pi))) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(2);
            var text=new PDFTextStripper().getText(pdf);
            for(int n=1;n<=50;n++)assertThat(text).contains(String.format("SKU-%03d",n));
            assertThat(text).contains("SAMPLE-ACCOUNT","Sample delivery address","Payment Terms","16,345.00","Price Gross","Page 2 /");
        }
    }

    @Test void zeroNetTaxAndUntrustedTextDoNotCreateFormulaOrDivideByZero() throws Exception {
        var pi=sample(1);pi.setCustomerName("=1+1");
        var item=pi.getItems().getFirst();item.setLineTotal(new BigDecimal("2.00"));item.setTaxAmount(new BigDecimal("2.00"));
        item.setUnitPrice(new BigDecimal("9999999999999999.99"));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(documents.excel(pi)))) {
            assertThat(book.getSheetAt(0).getRow(8).getCell(7).getCellType()).isEqualTo(CellType.STRING);
            assertThat(content(book)).contains("=1+1","Line tax: 2.00","9999999999999999.99").doesNotContain("#DIV/0!");
        }
    }

    @Test void longAddressesAndDescriptionsRemainReadableWithoutExceedingExcelRowHeight() throws Exception {
        var pi=sample(1);ObjectNode doc=json.valueToTree(pi.getDocument());
        doc.put("sellerAddress","示例地址".repeat(200)+"ADDRESS-END");
        pi.setDocument(json.treeToValue(doc,PiDocumentSnapshot.class));
        pi.getItems().getFirst().setSpecification("Long specification ".repeat(50)+"SPEC-END");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(documents.excel(pi)))) {
            for(var row:book.getSheetAt(0))assertThat(row.getHeightInPoints()).isLessThanOrEqualTo(409);
            assertThat(content(book)).contains("ADDRESS-END","SPEC-END");
        }
        try(var pdf=Loader.loadPDF(documents.pdf(pi))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("ADDRESS-END","SPEC-END");
        }
    }

    /** 按 312605030_Italy_DSB_Commercial Invoice：Nett/Gross/Total 为公式，折扣与运费在商品下方分行。 */
    @Test void v2UsesReferenceFormulasAndListsDiscountLikeFreight() throws Exception {
        var pi=sample(1);pi.setDocumentRenderVersion(PiEuropeDocumentRendererV2.VERSION);pi.setCustomerName("=1+1");
        var item=pi.getItems().getFirst();item.setQuantity(1);item.setUnitPrice(new BigDecimal("4200.00"));
        item.setDiscountAmount(new BigDecimal("200.00"));item.setTaxAmount(BigDecimal.ZERO);item.setLineTotal(new BigDecimal("4000.00"));
        pi.setSubtotal(new BigDecimal("4200.00"));pi.setDiscountAmount(new BigDecimal("200.00"));pi.setTaxAmount(BigDecimal.ZERO);
        pi.setFreightAmount(new BigDecimal("200.00"));pi.setOtherFeeAmount(BigDecimal.ZERO);pi.setTotalAmount(new BigDecimal("4200.00"));
        byte[] bytes=documents.excel(pi);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=book.getSheetAt(0);var line=sheet.getRow(32);
            assertThat(line.getCell(0).getStringCellValue()).doesNotContain("Discount");
            assertThat(line.getCell(8).getCellFormula()).isEqualTo("E33*F33");
            assertThat(line.getCell(10).getCellFormula()).isEqualTo("I33*(1+H33)");
            assertThat(line.getCell(7).getNumericCellValue()).isZero();
            assertThat(sheet.getRow(33).getCell(0).getStringCellValue()).isEqualTo("Discount");
            assertThat(sheet.getRow(33).getCell(10).getNumericCellValue()).isEqualTo(-200);
            assertThat(sheet.getRow(34).getCell(0).getStringCellValue()).isEqualTo("Freight Cost");
            assertThat(sheet.getRow(34).getCell(10).getNumericCellValue()).isEqualTo(200);
            assertThat(sheet.getMergedRegions()).contains(CellRangeAddress.valueOf("A34:J34"),CellRangeAddress.valueOf("K34:L34"),CellRangeAddress.valueOf("A35:J35"));
            assertThat(sheet.getRow(35).getCell(8).getStringCellValue()).isEqualTo("Total Price");
            assertThat(sheet.getRow(35).getCell(10).getCellFormula()).isEqualTo("SUM(K33:L35)");
            var evaluator=book.getCreationHelper().createFormulaEvaluator();
            assertThat(evaluator.evaluate(line.getCell(8)).getNumberValue()).isEqualTo(4200);
            assertThat(evaluator.evaluate(line.getCell(10)).getNumberValue()).isEqualTo(4200);
            assertThat(evaluator.evaluate(sheet.getRow(35).getCell(10)).getNumberValue()).isEqualTo(4200);
            // 已写入缓存值：未重算的查看器也能直接显示金额。
            assertThat(sheet.getRow(35).getCell(10).getNumericCellValue()).isEqualTo(4200);
            assertThat(sheet.getRow(8).getCell(7).getCellType()).isEqualTo(CellType.STRING);
        }
        try(var pdf=Loader.loadPDF(documents.pdf(pi))) {
            String text=new PDFTextStripper().getText(pdf);
            assertThat(text).contains("Discount","-200.00","Freight Cost","4,200.00").doesNotContain("Discount: 200","4,000.00");
        }
        writePreview("europe-pi-v2-discount",bytes,pi);
    }

    @Test void v2DerivesVatRateAndKeepsSystemTotal() throws Exception {
        // 3 × 100，行折扣 30，行税 56.7（= 21% × 270），全单追加折扣 5、税 3，运费 10，其他 2，应付 336.7。
        var pi=sample(1);pi.setDocumentRenderVersion(PiEuropeDocumentRendererV2.VERSION);
        byte[] bytes=documents.excel(pi);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=book.getSheetAt(0);var evaluator=book.getCreationHelper().createFormulaEvaluator();
            assertThat(sheet.getRow(32).getCell(7).getNumericCellValue()).isEqualTo(.21);
            assertThat(evaluator.evaluate(sheet.getRow(32).getCell(8)).getNumberValue()).isEqualTo(300);
            assertThat(evaluator.evaluate(sheet.getRow(32).getCell(10)).getNumberValue()).isCloseTo(363,within(1e-9));
            assertThat(sheet.getRow(33).getCell(0).getStringCellValue()).isEqualTo("Discount (incl. VAT)");
            assertThat(sheet.getRow(33).getCell(10).getNumericCellValue()).isCloseTo(-41.3,within(1e-9));
            assertThat(sheet.getRow(34).getCell(0).getStringCellValue()).isEqualTo("VAT");
            assertThat(sheet.getRow(35).getCell(0).getStringCellValue()).isEqualTo("Freight Cost");
            assertThat(sheet.getRow(36).getCell(0).getStringCellValue()).isEqualTo("Other Fees");
            assertThat(sheet.getRow(37).getCell(10).getCellFormula()).isEqualTo("SUM(K33:L37)");
            assertThat(evaluator.evaluate(sheet.getRow(37).getCell(10)).getNumberValue()).isCloseTo(336.7,within(1e-9));
        }
        writePreview("europe-pi-v2-vat",bytes,pi);
    }

    private void writePreview(String name, byte[] xlsx, PiView pi) throws Exception {
        String folder=System.getProperty("bike.pi.europe.preview.dir");
        if(folder==null) return;
        var path=Path.of(folder);Files.createDirectories(path);Files.write(path.resolve(name+".xlsx"),xlsx);
        byte[] pdfBytes=documents.pdf(pi);Files.write(path.resolve(name+".pdf"),pdfBytes);
        try(var pdf=Loader.loadPDF(pdfBytes)) {var renderer=new PDFRenderer(pdf);for(int page=0;page<pdf.getNumberOfPages();page++)ImageIO.write(renderer.renderImageWithDPI(page,100),"PNG",path.resolve(name+"-"+(page+1)+".png").toFile());}
    }

    private PiView sample(int count) throws Exception {
        var pi=PiDocumentServiceTest.sample(count);pi.setDocumentRenderVersion("PI_EUROPE_V1");pi.setPiNo("PI-EU-SAMPLE-001");pi.setCurrencyCode("EUR");
        var quantity=BigDecimal.valueOf(count);
        pi.setCustomerName("SAMPLE-BUYER");pi.setDiscountAmount(new BigDecimal("30").multiply(quantity).add(new BigDecimal("5")));pi.setTaxAmount(new BigDecimal("56.7").multiply(quantity).add(new BigDecimal("3")));pi.setSubtotal(new BigDecimal("300").multiply(quantity));
        pi.setFreightAmount(new BigDecimal("10"));pi.setOtherFeeAmount(new BigDecimal("2"));pi.setTotalAmount(new BigDecimal("326.7").multiply(quantity).add(BigDecimal.TEN));
        ObjectNode doc=json.valueToTree(pi.getDocument());
        doc.put("sellerName","Teewing SAMPLE Seller");doc.put("sellerAddress","Sample supplier address");doc.put("buyerAddress","Sample buyer address");
        doc.put("sellerCountry","NL");doc.put("buyerCountry","FR");doc.put("shippingAddress","Sample delivery address");
        doc.put("sellerEmail","sales@example.invalid");doc.put("buyerEmail","buyer@example.invalid");doc.put("bankName","SAMPLE BANK");doc.put("beneficiary","SAMPLE PAYEE");
        doc.put("sellerPhone","SAMPLE-PHONE");doc.put("buyerPhone","SAMPLE-PHONE");doc.put("paymentTerms","Sample payment terms");
        doc.put("deliveryTerms","Sample delivery terms");doc.put("sellerContact","Sample contact");doc.put("buyerContact","Sample contact");doc.put("issueDate","2026-11-19");
        doc.putObject("business").put("warehouseId",1).put("domesticShipment",false).put("collectionEntity","SAMPLE").put("salesChannel","CZECH_PI");
        pi.setDocument(json.treeToValue(doc,PiDocumentSnapshot.class));
        for(var item:pi.getItems()) {
            item.setProductName("Sample E-bike");item.setSpecification("Black / XL");item.setQuantity(3);item.setUnitPrice(new BigDecimal("100"));
            item.setDiscountAmount(new BigDecimal("30"));item.setTaxAmount(new BigDecimal("56.7"));item.setLineTotal(new BigDecimal("326.7"));item.setUnit("PCS");
        }
        return pi;
    }
    private String content(XSSFWorkbook book) {var text=new StringBuilder();book.getSheetAt(0).forEach(row->row.forEach(cell->text.append(cell).append('\n')));return text.toString();}
}
