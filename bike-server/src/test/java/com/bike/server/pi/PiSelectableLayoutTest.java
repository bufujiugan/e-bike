package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bike.server.common.BusinessException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class PiSelectableLayoutTest {
    @Test void selectableLayoutsPreserveTradeAndSnapshotValidation() {
        for (String trade : new String[]{"DOMESTIC", "EXPORT"}) {
            for (String layout : new String[]{"STANDARD_V3", "INVOICE_V1"}) {
                var d = PiPublishingMysqlTest.document();
                var template = new PiTemplate(1, d.templateCode(), d.templateVersion(), trade, layout,
                        d.sellerName(), d.sellerAddress(), d.sellerContact(), d.bankName(), d.bankAccount(),
                        d.beneficiary(), "测试", 1L, null, d.sellerCountry(), d.sellerContactName(), d.sellerPhone(), d.sellerEmail());
                var mapper = mock(PiTemplateMapper.class);
                when(mapper.find(d.templateCode(), d.templateVersion())).thenReturn(template);
                var service = new PiTemplateService(mapper, null, null, null);
                assertThat(PiTemplateService.isSelectableLayout(layout)).isTrue();
                assertThatCode(() -> service.requirePublished(d, trade)).doesNotThrowAnyException();
                assertThatThrownBy(() -> service.requirePublished(d, "DOMESTIC".equals(trade) ? "EXPORT" : "DOMESTIC"))
                        .isInstanceOf(BusinessException.class);
                assertThat(PiTemplateService.renderVersion(template)).isEqualTo("INVOICE_V1".equals(layout) ? "PI_V5" : "PI_V3");
            }
        }
        assertThat(PiTemplateService.isSelectableLayout("COUNTRY_UNKNOWN")).isFalse();
        assertThat(PiTemplateService.isSelectableLayout(null)).isFalse();
    }

    @Test void publishedReferenceLayoutsUseSynchronizedInvoicePdfVersion() {
        for (String trade : new String[]{"DOMESTIC", "EXPORT"}) {
            var layout = trade + "_V3";
            var template = new PiTemplate(1, trade, "3", trade, layout, "Seller", "Address", "Contact", "Bank",
                    "Account", "Beneficiary", "Reason", 1L, null, "CN", "Sales", "Phone", "sales@example.invalid");
            assertThat(PiTemplateService.renderVersion(template)).isEqualTo("PI_V5");
        }
    }

    @Test void bothLayoutsPreserveFiftyProductsAmountsAndTermsAcrossPages() throws Exception {
        var service = new PiDocumentService();
        for (String version : new String[]{"PI_V3", "PI_V5", "PI_INVOICE_V1"}) {
            var pi = PiDocumentServiceTest.sample(50);
            pi.setDocumentRenderVersion(version);
            byte[] pdfBytes = service.pdf(pi), excelBytes = service.excel(pi);
            try (var pdf = Loader.loadPDF(pdfBytes);
                 var book = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
                String pdfText = new PDFTextStripper().getText(pdf);
                var excelText = new StringBuilder();
                book.getSheetAt(0).forEach(row -> row.forEach(cell -> excelText.append(cell).append('\n')));
                assertThat(pdf.getNumberOfPages()).isGreaterThan(3);
                for (int index = 1; index <= 50; index++) {
                    String sku = String.format("SKU-%03d", index);
                    assertThat(pdfText).contains(sku);
                    assertThat(excelText).contains(sku);
                }
                for (String text : new String[]{"12345.67", "上海测试客户", "SAMPLE-ACCOUNT", "不构成真实交易"}) {
                    assertThat(pdfText).contains(text);
                    assertThat(excelText).contains(text);
                }
                assertThat(book.getSheetAt(0).getSheetName()).isEqualTo(Set.of("PI_V5", "PI_INVOICE_V1").contains(version) ? "INVOICE" : "PI");
                String preview = System.getProperty("bike.pi.layout.preview.dir");
                if (preview != null) {
                    Path folder = Path.of(preview); Files.createDirectories(folder);
                    Files.write(folder.resolve(version + ".pdf"), pdfBytes);
                    Files.write(folder.resolve(version + ".xlsx"), excelBytes);
                    var renderer = new PDFRenderer(pdf);
                    for (int page = 0; page < pdf.getNumberOfPages(); page++)
                        ImageIO.write(renderer.renderImageWithDPI(page, 100), "PNG", folder.resolve(version + "-" + page + ".png").toFile());
                }
            }
        }
    }
}
