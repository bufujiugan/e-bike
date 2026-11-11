package com.bike.server.pi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PiReferencePdfRendererTest {
    private final PiDocumentService documents = new PiDocumentService();

    @Test void longPartiesIdentifiersAmountsAndTermsRemainCompleteInsidePage() throws Exception {
        var pi = PiDocumentServiceTest.sample(1); pi.setDocumentRenderVersion("PI_V5");
        pi.setPiNo("PI-" + "1234567890".repeat(5) + "-END");
        pi.setSourceOrderNo("ORDER-" + "9876543210".repeat(5) + "-END");
        var json = new ObjectMapper().findAndRegisterModules();
        ObjectNode snapshot = json.valueToTree(pi.getDocument());
        snapshot.put("sellerAddress", "Seller line\n".repeat(65) + "SELLER-ADDRESS-END");
        snapshot.put("buyerAddress", "Buyer line\n".repeat(60) + "BUYER-ADDRESS-END");
        snapshot.put("sellerContactName", "Seller-contact-name"); snapshot.put("sellerContact", "Seller-free-contact");
        snapshot.put("buyerContactName", "Buyer-contact-name"); snapshot.put("buyerContact", "Buyer-free-contact");
        snapshot.put("paymentTerms", "Payment conditions must remain visible. ".repeat(40) + "PAYMENT-END");
        pi.setDocument(json.treeToValue(snapshot, PiDocumentSnapshot.class));
        pi.getItems().getFirst().setUnitPrice(new BigDecimal("90071992547409.93"));
        pi.getItems().getFirst().setLineTotal(new BigDecimal("180143985094819.86"));
        pi.setTotalAmount(new BigDecimal("180143985094829.86"));
        try (var pdf = Loader.loadPDF(documents.pdf(pi))) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(2);
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", "");
            assertThat(text).contains(pi.getPiNo(), pi.getSourceOrderNo(), "SELLER-ADDRESS-END", "BUYER-ADDRESS-END",
                    "Seller-contact-name", "Seller-free-contact", "Buyer-contact-name", "Buyer-free-contact", "PAYMENT-END",
                    "90071992547409.93", "180143985094819.86", "180143985094829.86");
            new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition position) {
                    assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(21);
                    assertThat(position.getXDirAdj() + position.getWidthDirAdj()).isLessThanOrEqualTo(574);
                    assertThat(position.getYDirAdj()).isBetween(1f, 830f);
                    super.processTextPosition(position);
                }
            }.getText(pdf);
        }
    }

    @Test void otherSellerKeepsItsProductImageWithoutReceivingTeewingLogo() throws Exception {
        var pi = PiDocumentServiceTest.sample(1); pi.setDocumentRenderVersion("PI_V5");
        try (var pdf = Loader.loadPDF(documents.pdf(pi))) {
            assertThat(pdf.getPage(0).getResources().getXObjectNames()).isEmpty();
        }
        var item = pi.getItems().getFirst(); item.setImageAttachmentId("product");
        var images = mock(PiImageService.class); when(images.bytes(item)).thenReturn(PiImageServiceTest.picture());
        ReflectionTestUtils.setField(documents, "images", images);
        try (var pdf = Loader.loadPDF(documents.pdf(pi))) {
            assertThat(pdf.getPage(0).getResources().getXObjectNames()).hasSize(1);
            assertThat(new PDFTextStripper().getText(pdf)).contains("SKU-001", "12345.67");
        }
    }

    @Test void historicalInvoiceVersionRetainsItsOriginalBilingualLayout() throws Exception {
        var pi = PiDocumentServiceTest.sample(1); pi.setDocumentRenderVersion("PI_INVOICE_V1");
        try (var pdf = Loader.loadPDF(documents.pdf(pi))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("PROFORMA INVOICE / 形式发票", "SELLER / 卖方",
                    "BUYER / 买方", "采购办公室", "Code: C-TEST", "收款银行").doesNotContain("Products provided", "Net total");
        }
    }
}
