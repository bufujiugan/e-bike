package com.bike.server.pi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PiSalesDocumentRoutingTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void salesChannelOverridesSelectableBankTemplateLayoutForPreviewAndArchive() throws Exception {
        for (String channel : List.of("CHINA_SALES", "CZECH_PI")) {
            for (String layout : List.of("STANDARD_V3", "INVOICE_V1", "EXPORT_V3", "DOMESTIC_V3")) {
                var pi = sample(channel, "EXPORT");
                var d = pi.getDocument();
                var template = new PiTemplate(1, d.templateCode(), d.templateVersion(), "EXPORT", layout,
                        d.sellerName(), d.sellerAddress(), d.sellerContact(), d.bankName(), d.bankAccount(),
                        d.beneficiary(), "测试", 1L, null, d.sellerCountry(), d.sellerContactName(), d.sellerPhone(), d.sellerEmail());
                var templates = mock(PiTemplateMapper.class);
                when(templates.find(d.templateCode(), d.templateVersion())).thenReturn(template);
                new PiTemplateService(templates, null, null, null).selectRenderer(pi);
                String expected = channel.equals("CHINA_SALES") ? "PI_V5" : "PI_EUROPE_V1";
                assertThat(pi.getDocumentRenderVersion()).isEqualTo(expected);
                var mapper = mock(PiMapper.class);
                when(mapper.selectItems(pi.getId())).thenReturn(pi.getItems());
                var archive = new PiExportArchive(null, mapper, null, json, null);
                ReflectionTestUtils.setField(archive, "templates", templates);
                var captured = archive.publicSnapshot(pi);
                assertThat(captured.getDocumentRenderVersion()).isEqualTo(expected);
                assertThat(captured.getDocument().bankAccount()).isEqualTo(d.bankAccount());
                assertThat(json.readValue(json.writeValueAsString(captured), PiView.class).getDocumentRenderVersion()).isEqualTo(expected);
            }
        }
    }

    @Test void exportsTheRequiredLayoutForChinaExportAndBothEuropeTrades() throws Exception {
        var documents = new PiDocumentService();
        for (String scenario : List.of("CHINA_EXPORT", "EUROPE_DOMESTIC", "EUROPE_EXPORT")) {
            boolean china = scenario.equals("CHINA_EXPORT");
            var pi = sample(china ? "CHINA_SALES" : "CZECH_PI", scenario.endsWith("DOMESTIC") ? "DOMESTIC" : "EXPORT");
            pi.setDocumentRenderVersion(PiTemplateService.renderVersion(null, pi.getDocument()));
            byte[] xlsx = documents.excel(pi), pdfBytes = documents.pdf(pi);
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx)); var pdf = Loader.loadPDF(pdfBytes)) {
                var sheet = workbook.getSheetAt(0);
                assertThat(sheet.getSheetName()).isEqualTo(china ? "INVOICE" : "EUROPE PI");
                assertThat(workbook.getPrintArea(0)).contains(china ? "$A$1:$E$" : "$A$1:$L$");
                var content = new StringBuilder();
                sheet.forEach(row -> row.forEach(cell -> content.append(cell).append('\n')));
                assertThat(content).contains(pi.getPiNo(), pi.getCustomerName(), "SAMPLE-ACCOUNT");
                assertThat(content).doesNotContain("TW2026072801", "Chris Cadle", "ride.built", "47413320305");
                assertThat(new PDFTextStripper().getText(pdf)).contains(pi.getPiNo(), "SAMPLE-ACCOUNT");
                String preview = System.getProperty("bike.pi.sales.preview.dir");
                if (preview != null) {
                    var directory = Path.of(preview); Files.createDirectories(directory);
                    Files.write(directory.resolve(scenario + ".xlsx"), xlsx);
                    Files.write(directory.resolve(scenario + ".pdf"), pdfBytes);
                    var renderer = new PDFRenderer(pdf);
                    for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                        ImageIO.write(renderer.renderImageWithDPI(page, 100), "PNG", directory.resolve(scenario + "-" + page + ".png").toFile());
                    }
                }
            }
        }
    }

    @Test void queuedLegacySnapshotsRetainTheirSavedRenderer() throws Exception {
        var pi = sample("CHINA_SALES", "EXPORT");
        pi.setDocumentRenderVersion("PI_V3");
        var queued = json.readValue(json.writeValueAsString(pi), PiView.class);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(new PiDocumentService().excel(queued)))) {
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("PI");
        }
    }

    private PiView sample(String channel, String trade) throws Exception {
        var pi = PiDocumentServiceTest.sample(1);
        pi.setPiNo("PI-SCENARIO-SAMPLE"); pi.setTradeType(trade); pi.setCustomerName("Sample Buyer");
        pi.setSourceOrderNo("SO-SCENARIO-SAMPLE");
        pi.setCurrencyCode(channel.equals("CHINA_SALES") ? "USD" : "EUR");
        pi.setSubtotal(new java.math.BigDecimal("250.00"));
        pi.setDiscountAmount(new java.math.BigDecimal("6.00"));
        pi.setTaxAmount(new java.math.BigDecimal("3.50"));
        pi.setFreightAmount(new java.math.BigDecimal("10.00"));
        pi.setOtherFeeAmount(new java.math.BigDecimal("2.00"));
        pi.setTotalAmount(new java.math.BigDecimal("259.50"));
        ObjectNode document = json.valueToTree(pi.getDocument());
        document.put("paymentTerms", "Sample payment terms");
        document.put("deliveryTerms", "Sample delivery terms");
        document.putObject("business").put("warehouseId", 1).put("domesticShipment", true)
                .put("collectionEntity", "SAMPLE").put("salesChannel", channel);
        pi.setDocument(json.treeToValue(document, PiDocumentSnapshot.class));
        return pi;
    }
}
