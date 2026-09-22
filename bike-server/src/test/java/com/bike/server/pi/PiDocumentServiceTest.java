package com.bike.server.pi;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class PiDocumentServiceTest {
    private final PiDocumentService service = new PiDocumentService();

    @Test void v2RendersImageUnitsDatesAndOtherFeesWhileLegacyRendererStaysFrozen()throws Exception {
        var pi=sample(5);var d=pi.getDocument();
        pi.setDocument(new PiDocumentSnapshot(d.sellerName(),d.sellerAddress(),d.sellerContact(),d.buyerAddress(),d.buyerContact(),d.bankName(),d.bankAccount(),d.beneficiary(),d.paymentTerms(),d.deliveryTerms(),d.templateCode(),d.templateVersion(),
                java.time.LocalDate.of(2026,9,23),java.time.LocalDate.of(2026,10,23),"上海市示例收货地址","海运 / Sea","FOB Shanghai","DE","CN"));
        pi.setTradeType("EXPORT");pi.setOtherFeeAmount(new BigDecimal("12.34"));
        pi.setSubtotal(new BigDecimal("1250.00"));pi.setDiscountAmount(new BigDecimal("5.00"));
        pi.setTaxAmount(new BigDecimal("2.50"));pi.setTotalAmount(new BigDecimal("1269.84"));
        pi.setShipmentRequiredAmount(pi.getTotalAmount());
        var images=org.mockito.Mockito.mock(PiImageService.class);org.mockito.Mockito.when(images.bytes(org.mockito.ArgumentMatchers.any())).thenReturn(PiImageServiceTest.picture());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"images",images);
        for(var item:pi.getItems()){item.setUnit("PCS");item.setImageAttachmentId("image-id");}
        byte[] pdfBytes=service.pdf(pi);
        try(var pdf=Loader.loadPDF(pdfBytes)) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("PCS","2026-09-23","2026-10-23","FOB Shanghai","12.34","Sea");
            assertThat(pdf.getPage(0).getResources().getXObjectNames()).isNotEmpty();
            String output=System.getProperty("bike.pi.preview.dir");
            if(output!=null){Path folder=Path.of(output);Files.createDirectories(folder);Files.write(folder.resolve("pi-v2-images.pdf"),pdfBytes);Files.write(folder.resolve("pi-v2-images.xlsx"),service.excel(pi));var renderer=new PDFRenderer(pdf);for(int i=0;i<pdf.getNumberOfPages();i++)ImageIO.write(renderer.renderImageWithDPI(i,110),"PNG",folder.resolve("pi-v2-images-"+(i+1)+".png").toFile());}
        }
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.excel(pi)))){assertThat(book.getAllPictures()).hasSize(5);}
        pi.setDocumentRenderVersion("PI_V1");
        try(var pdf=Loader.loadPDF(service.pdf(pi))){assertThat(new PDFTextStripper().getText(pdf)).doesNotContain("2026-10-23","FOB Shanghai","12.34");}
    }

    @Test void chineseInvoicePreservesEveryLineAndTermsAcrossPages() throws Exception {
        PiView pi = sample(50);
        byte[] bytes = service.pdf(pi);
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(3);
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("惠州电动自行车有限公司", "上海测试客户", "SKU-050", "12345.67", "收款银行", "草稿 / DRAFT");
            for (int i = 1; i <= 50; i++) assertThat(text).contains(String.format("SKU-%03d", i));
            for (int i = 1; i <= pdf.getNumberOfPages(); i++) assertThat(text).contains("Page " + i + " / " + pdf.getNumberOfPages());
            String output = System.getProperty("bike.pi.preview.dir");
            if (output != null) {
                Path folder = Path.of(output); Files.createDirectories(folder);
                Files.write(folder.resolve("pi-chinese-50-lines.pdf"), bytes);
                Files.write(folder.resolve("pi-chinese-50-lines.xlsx"),service.excel(pi));
                PDFRenderer renderer = new PDFRenderer(pdf);
                for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                    ImageIO.write(renderer.renderImageWithDPI(i, 110), "PNG", folder.resolve("pi-page-" + (i + 1) + ".png").toFile());
                }
            }
        }
    }

    @Test void excelPreservesHighPrecisionAmountsAndCannotInterpretCustomerTextAsFormula() throws Exception {
        PiView pi = sample(1);
        pi.setCustomerName("=HYPERLINK(\"https://example.invalid\",\"test\")");
        pi.getItems().getFirst().setUnitPrice(new BigDecimal("9999999999999999.99"));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.excel(pi)))) {
            var sheet = workbook.getSheetAt(0);
            StringBuilder content = new StringBuilder();
            sheet.forEach(row -> row.forEach(cell -> {
                assertThat(cell.getCellType()).isNotEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);
                content.append(cell).append('\n');
            }));
            assertThat(content).contains("9999999999999999.99", "=HYPERLINK", "收款银行", "12345.67", "SKU-001");
            assertThat(sheet.getRepeatingRows()).isNotNull();
        }
    }

    @Test void excelUsesNumericAmountsTextCodesAndExplicitPrecisionFallback()throws Exception {
        PiView pi=sample(1);pi.getItems().getFirst().setSkuCode("0000123");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.excel(pi)))) {
            var sheet=book.getSheetAt(0);org.apache.poi.ss.usermodel.Row product=null,total=null;
            for(var row:sheet)if(row.getCell(0)!=null){if("0000123".equals(row.getCell(0).toString()))product=row;if(row.getCell(0).toString().startsWith("应付总额"))total=row;}
            assertThat(product).isNotNull();assertThat(product.getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(product.getCell(3).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);assertThat(product.getCell(3).getNumericCellValue()).isEqualTo(125.00);
            assertThat(total).isNotNull();assertThat(total.getCell(6).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);assertThat(total.getCell(6).getNumericCellValue()).isEqualTo(12345.67);
        }
        pi.getItems().getFirst().setUnitPrice(new BigDecimal("90071992547409.93"));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.excel(pi)))) {
            var row=java.util.stream.StreamSupport.stream(book.getSheetAt(0).spliterator(),false).filter(r->r.getCell(0)!=null&&"0000123".equals(r.getCell(0).toString())).findFirst().orElseThrow();
            assertThat(row.getCell(3).getStringCellValue()).isEqualTo("90071992547409.93");assertThat(row.getCell(3).getCellComment()).isNotNull();
        }
    }

    static PiView sample(int count) {
        PiView pi = new PiView(); pi.setId(1L); pi.setPiNo("PI-DOCUMENT-ACCEPTANCE"); pi.setRevision(1);
        pi.setCustomerName("上海测试客户"); pi.setCustomerCode("C-TEST"); pi.setCurrencyCode("CNY");
        pi.setTradeType("DOMESTIC"); pi.setStatus("DRAFT");
        pi.setSubtotal(new BigDecimal("12500.00")); pi.setDiscountAmount(new BigDecimal("200.00"));
        pi.setTaxAmount(new BigDecimal("35.67")); pi.setFreightAmount(new BigDecimal("10.00"));
        pi.setTotalAmount(new BigDecimal("12345.67")); pi.setDepositRequiredAmount(new BigDecimal("1000.00"));
        pi.setShipmentRequiredAmount(new BigDecimal("12345.67"));
        pi.setDocument(new PiDocumentSnapshot("惠州电动自行车有限公司", "广东省惠州市测试路一百号", "测试联系人 / Test contact",
                "上海市测试区测试路二号", "采购办公室", "测试银行（非真实收款资料）", "SAMPLE-ACCOUNT", "惠州电动自行车有限公司",
                "本文件仅用于系统版面验收，不构成真实交易。\n" + "已确认的付款条款应由授权业务人员填写并保留在单据快照中。".repeat(8),
                "交付地点及运输责任按双方确认条款。", "GENERIC-PREVIEW", "1"));
        var items = new ArrayList<PiItemView>();
        for (int n = 1; n <= count; n++) {
            PiItemView item = new PiItemView(); item.setSkuId((long) n); item.setSkuCode(String.format("SKU-%03d", n));
            item.setProductName("城市通勤电动自行车 " + n); item.setSpecification("颜色：石墨灰 / 48V / 20英寸 / Urban commuter");
            item.setQuantity(2); item.setUnitPrice(new BigDecimal("125.00")); item.setDiscountAmount(new BigDecimal("1.00"));
            item.setTaxAmount(new BigDecimal("0.50")); item.setLineTotal(new BigDecimal("249.50")); items.add(item);
        }
        pi.setItems(items); return pi;
    }
}
