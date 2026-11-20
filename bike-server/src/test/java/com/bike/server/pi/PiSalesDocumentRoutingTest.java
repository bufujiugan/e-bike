package com.bike.server.pi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 销售类型决定单据版式：外销→260728英国builtPI，欧洲→France_1104_PI，无入口标识的国内内销→购销合同。 */
class PiSalesDocumentRoutingTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void salesTypeOverridesSelectableBankTemplateLayoutForPreviewAndArchive() throws Exception {
        record Case(String channel, String trade, boolean domesticShipment, String expected) {}
        for (var scenario : List.of(
                new Case("CHINA_SALES", "EXPORT", true, PiUkInvoiceRenderer.VERSION),
                new Case("CHINA_SALES", "EXPORT", false, PiUkInvoiceRenderer.VERSION),
                new Case("CZECH_PI", "EXPORT", false, PiEuropeDocumentRendererV2.VERSION),
                new Case("CZECH_PI", "DOMESTIC", true, PiEuropeDocumentRendererV2.VERSION),
                // 没有销售入口标识的历史单据：按出货地与贸易类型归类，不再一律落到英国 PI。
                new Case(null, "DOMESTIC", true, PiDomesticContractRenderer.VERSION),
                new Case(null, "DOMESTIC", false, PiEuropeDocumentRendererV2.VERSION),
                new Case(null, "EXPORT", true, PiUkInvoiceRenderer.VERSION),
                new Case(null, "EXPORT", false, PiEuropeDocumentRendererV2.VERSION))) {
            for (String layout : List.of("STANDARD_V3", "INVOICE_V1", "EXPORT_V3", "DOMESTIC_V3")) {
                var pi = sample(scenario.channel(), scenario.trade(), scenario.domesticShipment());
                var d = pi.getDocument();
                var template = new PiTemplate(1, d.templateCode(), d.templateVersion(), scenario.trade(), layout,
                        d.sellerName(), d.sellerAddress(), d.sellerContact(), d.bankName(), d.bankAccount(),
                        d.beneficiary(), "测试", 1L, null, d.sellerCountry(), d.sellerContactName(), d.sellerPhone(), d.sellerEmail());
                var templates = mock(PiTemplateMapper.class);
                when(templates.find(d.templateCode(), d.templateVersion())).thenReturn(template);
                new PiTemplateService(templates, null, null, null).selectRenderer(pi);
                assertThat(pi.getDocumentRenderVersion()).as(scenario.toString()).isEqualTo(scenario.expected());
                var mapper = mock(PiMapper.class);
                when(mapper.selectItems(pi.getId())).thenReturn(pi.getItems());
                var archive = new PiExportArchive(null, mapper, null, json, null);
                ReflectionTestUtils.setField(archive, "templates", templates);
                var captured = archive.publicSnapshot(pi);
                assertThat(captured.getDocumentRenderVersion()).isEqualTo(scenario.expected());
                assertThat(captured.getDocument().swiftCode()).isEqualTo("SAMPLESWIFT");
                assertThat(json.readValue(json.writeValueAsString(captured), PiView.class).getDocumentRenderVersion()).isEqualTo(scenario.expected());
            }
        }
    }

    @Test void legacySnapshotsWithoutBusinessTermsKeepTheirTemplateLayout() throws Exception {
        var pi = sample("CHINA_SALES", "EXPORT", true);
        pi.setDocument(PiDocumentServiceTest.sample(1).getDocument());
        assertThat(PiTemplateService.renderVersion(null, pi.getDocument(), "EXPORT")).isEqualTo("PI_V1");
    }

    @Test void exportsTheReferenceLayoutForEverySalesType() throws Exception {
        var documents = new PiDocumentService();
        var formatter = new DataFormatter(java.util.Locale.UK);
        record Case(String name, String channel, String trade, boolean domesticShipment, String sheet, String area) {}
        for (var scenario : List.of(
                new Case("CHINA_EXPORT", "CHINA_SALES", "EXPORT", true, "INVOICE", "$A$1:$E$"),
                new Case("EUROPE_DOMESTIC", "CZECH_PI", "DOMESTIC", false, "Proforma Invoice", "$A$1:$L$"),
                new Case("EUROPE_EXPORT", "CZECH_PI", "EXPORT", false, "Proforma Invoice", "$A$1:$L$"),
                new Case("LEGACY_DOMESTIC", null, "DOMESTIC", true, "购销合同", "$A$1:$K$"))) {
            var pi = sample(scenario.channel(), scenario.trade(), scenario.domesticShipment());
            pi.setDocumentRenderVersion(PiTemplateService.renderVersion(null, pi.getDocument(), scenario.trade()));
            byte[] xlsx = documents.excel(pi), pdfBytes = documents.pdf(pi);
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx)); var pdf = Loader.loadPDF(pdfBytes)) {
                var sheet = workbook.getSheetAt(0);
                assertThat(sheet.getSheetName()).isEqualTo(scenario.sheet());
                assertThat(workbook.getPrintArea(0)).contains(scenario.area());
                var content = new StringBuilder();
                sheet.forEach(row -> row.forEach(cell -> content.append(formatter.formatCellValue(cell)).append('\n')));
                assertThat(content).contains(pi.getPiNo(), pi.getCustomerName(), "SAMPLE-ACCOUNT");
                // 参考文件中的历史客户、单号、银行和价格不得进入新单据。
                assertThat(content).doesNotContain("TW2026072801", "Chris Cadle", "ride.built", "47413320305", "SCBLHKHH",
                        "RACE COMPANY", "NL63AINH1797316077", "3026110401", "广州塔威", "TWEBFO8", "R1 / DRAFT", "Revision");
                String text = new PDFTextStripper().getText(pdf);
                assertThat(text).contains(pi.getPiNo(), "SAMPLE-ACCOUNT");
                switch (scenario.name()) {
                    case "CHINA_EXPORT" -> {
                        assertThat(formatter.formatCellValue(sheet.getRow(14).getCell(1))).isEqualTo(pi.getPiNo());
                        assertThat(formatter.formatCellValue(sheet.getRow(14).getCell(0))).isEqualTo("SO-SCENARIO-SAMPLE");
                        assertThat(formatter.formatCellValue(sheet.getRow(14).getCell(2))).isEqualTo("19.11.2026");
                        assertThat(formatter.formatCellValue(sheet.getRow(11).getCell(0))).isEqualTo("EBIKE and FRAME");
                        assertThat(content).contains("FOB Shenzhen", "SWIFT Code: SAMPLESWIFT", "Bank Address: Sample bank address",
                                "Bank Code: 003", "Branch Code: 474", "Net total", "Discount", "Unit Price (USD)");
                        assertThat(text).contains("Proforma Invoice", "Net total", "SAMPLESWIFT");
                        var net = findRow(sheet, "Net total");
                        assertThat(sheet.getRow(net).getCell(4).getNumericCellValue()).isEqualTo(259.50);
                        assertThat(sheet.getRow(net).getCell(2).getNumericCellValue()).isEqualTo(2);
                    }
                    case "EUROPE_DOMESTIC", "EUROPE_EXPORT" -> {
                        assertThat(formatter.formatCellValue(sheet.getRow(2).getCell(7))).isEqualTo("No." + pi.getPiNo());
                        assertThat(sheet.getRow(3).getCell(8).getCellType()).isEqualTo(CellType.NUMERIC);
                        assertThat(formatter.formatCellValue(sheet.getRow(3).getCell(8))).isEqualTo("19.11.2026");
                        assertThat(content).contains("KVK-123", "NL-VAT-001", "SAMPLESWIFT", "FR-BUYER-VAT", "Dispatch warehouse address", "Downpayment invoices");
                        assertThat(text).contains("Total Price", "SAMPLESWIFT");
                    }
                    default -> {
                        assertThat(formatter.formatCellValue(sheet.getRow(0).getCell(10))).isEqualTo("2026-11-19");
                        assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(10))).isEqualTo(pi.getPiNo());
                        assertThat(sheet.getRow(21).getCell(10).getNumericCellValue()).isEqualTo(259.50);
                        assertThat(sheet.getRow(21).getCell(5).getNumericCellValue()).isEqualTo(2);
                        assertThat(text).contains("购销合同");
                    }
                }
                String preview = System.getProperty("bike.pi.sales.preview.dir");
                if (preview != null) {
                    var directory = Path.of(preview); Files.createDirectories(directory);
                    Files.write(directory.resolve(scenario.name() + ".xlsx"), xlsx);
                    Files.write(directory.resolve(scenario.name() + ".pdf"), pdfBytes);
                    var renderer = new PDFRenderer(pdf);
                    for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                        ImageIO.write(renderer.renderImageWithDPI(page, 100), "PNG", directory.resolve(scenario.name() + "-" + page + ".png").toFile());
                    }
                }
            }
        }
    }

    @Test void longItemListsPaginateWithRepeatedHeaderAndKeepEveryLine() throws Exception {
        for (String channel : new String[]{"CHINA_SALES", "CZECH_PI", null}) {
            var pi = sample(channel, channel == null ? "DOMESTIC" : "EXPORT", channel == null);
            var many = PiDocumentServiceTest.sample(45).getItems();
            pi.setItems(many);
            pi.setDocumentRenderVersion(PiTemplateService.renderVersion(null, pi.getDocument(), pi.getTradeType()));
            try (var pdf = Loader.loadPDF(new PiDocumentService().pdf(pi))) {
                assertThat(pdf.getNumberOfPages()).as(String.valueOf(channel)).isGreaterThan(1);
                String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", "");
                assertThat(text).contains("城市通勤电动自行车45", "城市通勤电动自行车1");
            }
        }
    }

    @Test void queuedLegacySnapshotsRetainTheirSavedRenderer() throws Exception {
        var pi = sample("CHINA_SALES", "EXPORT", true);
        for (var entry : java.util.Map.of("PI_V3", "PI", "PI_V5", "INVOICE", "PI_EUROPE_V1", "EUROPE PI").entrySet()) {
            pi.setDocumentRenderVersion(entry.getKey());
            var queued = json.readValue(json.writeValueAsString(pi), PiView.class);
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(new PiDocumentService().excel(queued)))) {
                assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo(entry.getValue());
            }
        }
    }

    @Test void teewingSellerKeepsTheReferenceLogoOnUkAndEuropeLayouts() throws Exception {
        for (String channel : List.of("CHINA_SALES", "CZECH_PI")) {
            var pi = sample(channel, "EXPORT", !"CZECH_PI".equals(channel));
            ObjectNode document = json.valueToTree(pi.getDocument());
            document.put("sellerName", "Teewing Industrial (HK) Co., Limited");
            pi.setDocument(json.treeToValue(document, PiDocumentSnapshot.class));
            pi.setDocumentRenderVersion(PiTemplateService.renderVersion(null, pi.getDocument(), "EXPORT"));
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(new PiDocumentService().excel(pi)));
                 var pdf = Loader.loadPDF(new PiDocumentService().pdf(pi))) {
                assertThat(workbook.getAllPictures()).hasSize(1);
                assertThat(pdf.getPage(0).getResources().getXObjectNames()).isNotEmpty();
            }
            String preview = System.getProperty("bike.pi.sales.preview.dir");
            if (preview != null) Files.write(Path.of(preview).resolve("TEEWING_" + channel + ".pdf"), new PiDocumentService().pdf(pi));
        }
    }

    @Test void ibanLabelIsOnlyUsedForIbanFormattedAccounts() {
        assertThat(PiEuropeDocumentRendererV2.iban("NL63 AINH 1797 3160 77")).isTrue();
        assertThat(PiEuropeDocumentRendererV2.iban("47413320305")).isFalse();
        assertThat(PiEuropeDocumentRendererV2.iban("SAMPLE-ACCOUNT")).isFalse();
    }

    private static int findRow(org.apache.poi.ss.usermodel.Sheet sheet, String label) {
        for (var row : sheet) if (row.getCell(0) != null && label.equals(row.getCell(0).toString())) return row.getRowNum();
        throw new AssertionError("row not found: " + label);
    }

    private PiView sample(String channel, String trade, boolean domesticShipment) throws Exception {
        var pi = PiDocumentServiceTest.sample(1);
        pi.setPiNo("PI-SCENARIO-SAMPLE"); pi.setTradeType(trade); pi.setCustomerName("Sample Buyer");
        pi.setSourceOrderNo("SO-SCENARIO-SAMPLE");
        pi.setCurrencyCode("CHINA_SALES".equals(channel) ? "USD" : "CZECH_PI".equals(channel) ? "EUR" : "CNY");
        pi.setSubtotal(new java.math.BigDecimal("250.00"));
        pi.setDiscountAmount(new java.math.BigDecimal("6.00"));
        pi.setTaxAmount(new java.math.BigDecimal("3.50"));
        pi.setFreightAmount(new java.math.BigDecimal("10.00"));
        pi.setOtherFeeAmount(new java.math.BigDecimal("2.00"));
        pi.setTotalAmount(new java.math.BigDecimal("259.50"));
        ObjectNode document = json.valueToTree(pi.getDocument());
        document.put("paymentTerms", "Sample payment terms");
        document.put("deliveryTerms", "Sample delivery terms");
        document.put("tradeTerms", "FOB Shenzhen");
        document.put("issueDate", LocalDate.of(2026, 11, 19).toString());
        document.put("bankAddress", "Sample bank address");
        document.put("swiftCode", "SAMPLESWIFT");
        document.put("bankCode", "003");
        document.put("branchCode", "474");
        document.put("sellerRegistrationNo", "KVK-123");
        document.put("sellerVatNo", "NL-VAT-001");
        document.put("buyerVatNo", "FR-BUYER-VAT");
        document.put("dispatchAddress", "Dispatch warehouse address");
        document.put("productCategory", "EBIKE and FRAME");
        var business = document.putObject("business").put("warehouseId", 1).put("domesticShipment", domesticShipment)
                .put("collectionEntity", "SAMPLE");
        if (channel != null) business.put("salesChannel", channel);
        pi.setDocument(json.treeToValue(document, PiDocumentSnapshot.class));
        return pi;
    }
}
