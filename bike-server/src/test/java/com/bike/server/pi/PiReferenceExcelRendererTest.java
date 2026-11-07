package com.bike.server.pi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PiReferenceExcelRendererTest {
    private final PiDocumentService documents = new PiDocumentService();

    @Test void referenceLayoutHasFiveColumnsPortraitPrintAndOnlyBrandImage() throws Exception {
        PiView pi = referenceInvoice();
        byte[] bytes = documents.excel(pi);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("INVOICE");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Proforma Invoice");
            assertThat(sheet.getMergedRegions()).contains(CellRangeAddress.valueOf("B3:D3"),
                    CellRangeAddress.valueOf("A6:B6"), CellRangeAddress.valueOf("D6:E6"));
            assertThat(sheet.getRow(16).getLastCellNum()).isEqualTo((short) 5);
            assertThat(sheet.getRow(16).getCell(3).getStringCellValue()).isEqualTo("Unit Price (HKD)");
            assertThat(sheet.getRow(17).getCell(0).getNumericCellValue()).isEqualTo(1);
            assertThat(sheet.getRow(17).getCell(2).getNumericCellValue()).isEqualTo(1);
            assertThat(sheet.getRow(17).getCell(3).getNumericCellValue()).isEqualTo(435);
            assertThat(sheet.getRow(17).getCell(4).getNumericCellValue()).isEqualTo(435);
            assertThat(sheet.getRow(18).getCell(0).getStringCellValue()).isEqualTo("Net total");
            assertThat(sheet.getRow(18).getCell(4).getNumericCellValue()).isEqualTo(435);
            assertThat(sheet.getRow(14).getCell(2).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 7));
            assertThat(new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(sheet.getRow(14).getCell(2))).isEqualTo("07.11.2026");
            assertThat(sheet.getRow(14).getCell(0).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(((org.apache.poi.xssf.usermodel.XSSFCellStyle) sheet.getRow(13).getCell(0).getCellStyle())
                    .getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FF00B0F0");
            assertThat(sheet.getPrintSetup().getLandscape()).isFalse();
            assertThat(sheet.getPrintSetup().getFitHeight()).isZero();
            assertThat(sheet.getRepeatingRows()).isEqualTo(CellRangeAddress.valueOf("17:17"));
            assertThat(book.getPrintArea(0)).contains("$A$1:$E$");
            assertThat(book.getAllPictures()).hasSize(1);
            try (var logo = getClass().getResourceAsStream("/pi/teewing-logo.png")) {
                assertThat(book.getAllPictures().getFirst().getData()).isEqualTo(logo.readAllBytes());
            }
            assertThat(content(book)).contains("FCA Shenzhen", "SAMPLE-ACCOUNT", "Sample Bank", "Mail: sales@example.invalid")
                    .doesNotContain("SWIFT", "44796571492", "Signature");
        }
        String preview = System.getProperty("bike.pi.reference.preview.dir");
        if (preview != null) {
            Path folder = Path.of(preview); Files.createDirectories(folder);
            Files.write(folder.resolve("pi-reference-preview.xlsx"), bytes);
        }
    }

    @Test void newSnapshotUsesReferenceVersionWhileQueuedV3AndV2KeepOriginalLayout() throws Exception {
        var template = new PiTemplate(1, "EXPORT", "1", "EXPORT", "EXPORT_V3",
                "Seller", "Address", "Contact", "Bank", "Account", "Beneficiary",
                "Reason", 1L, null, "CN", "Sales", "Phone", "sales@example.invalid");
        var templates = mock(PiTemplateMapper.class); when(templates.find("EXPORT", "1")).thenReturn(template);
        var selector = new PiTemplateService(templates, null, null, null);
        var pi = referenceInvoice(); selector.selectRenderer(pi);
        assertThat(pi.getDocumentRenderVersion()).isEqualTo("PI_V4");
        var mapper = mock(PiMapper.class); when(mapper.selectItems(pi.getId())).thenReturn(pi.getItems());
        var archive = new PiExportArchive(null, mapper, null, new ObjectMapper().findAndRegisterModules(), null);
        ReflectionTestUtils.setField(archive, "templates", templates);
        assertThat(archive.publicSnapshot(pi).getDocumentRenderVersion()).isEqualTo("PI_V4");
        for (String version : new String[]{"PI_V2", "PI_V3"}) {
            pi.setDocumentRenderVersion(version);
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(documents.excel(pi)))) {
                assertThat(book.getSheetAt(0).getSheetName()).isEqualTo("PI");
                assertThat(book.getSheetAt(0).getPrintSetup().getLandscape()).isTrue();
            }
        }
    }

    @Test void adjustmentsRetainSavedLineAndDocumentAmounts() throws Exception {
        var pi = referenceInvoice();
        pi.setDiscountAmount(new BigDecimal("5.00")); pi.setTaxAmount(new BigDecimal("2.50"));
        pi.setFreightAmount(new BigDecimal("10.00")); pi.setOtherFeeAmount(new BigDecimal("12.34"));
        pi.setTotalAmount(new BigDecimal("454.84"));
        pi.getItems().getFirst().setDiscountAmount(new BigDecimal("5.00"));
        pi.getItems().getFirst().setTaxAmount(new BigDecimal("2.50"));
        pi.getItems().getFirst().setLineTotal(new BigDecimal("432.50"));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(documents.excel(pi)))) {
            assertThat(book.getSheetAt(0).getRow(17).getCell(4).getNumericCellValue()).isEqualTo(432.50);
            assertThat(numberFor(book, "Subtotal")).isEqualTo(435);
            assertThat(numberFor(book, "Discount")).isEqualTo(-5);
            assertThat(numberFor(book, "Tax")).isEqualTo(2.5);
            assertThat(numberFor(book, "Freight")).isEqualTo(10);
            assertThat(numberFor(book, "Other fees")).isEqualTo(12.34);
            assertThat(numberFor(book, "Net total")).isEqualTo(454.84);
        }
    }

    @Test void exactAmountsAndUserTextAreNeverEvaluatedAsFormulas() throws Exception {
        var pi = referenceInvoice();
        pi.getItems().getFirst().setUnitPrice(new BigDecimal("90071992547409.93"));
        pi.getItems().getFirst().setProductName("=1+1");
        pi.getItems().getFirst().setSkuCode("0000123");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(documents.excel(pi)))) {
            var price = book.getSheetAt(0).getRow(17).getCell(3);
            assertThat(price.getCellType()).isEqualTo(CellType.STRING);
            assertThat(price.getStringCellValue()).isEqualTo("90071992547409.93");
            assertThat(price.getCellComment()).isNotNull();
            book.getSheetAt(0).forEach(row -> row.forEach(cell -> assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA)));
            assertThat(content(book)).contains("=1+1", "0000123");
        }
    }

    @Test void longTermsAndFiftyProductsRemainCompleteWithoutOversizedRows() throws Exception {
        var pi = referenceInvoice(); var items = new ArrayList<PiItemView>();
        for (int index = 1; index <= 50; index++) {
            var item = new PiItemView(); item.setSkuCode("SKU-" + index); item.setProductName("Product " + index);
            item.setSpecification("规格参数很长，需要自动换行并保留全文。".repeat(80) + "END-" + index);
            item.setQuantity(index); item.setUnitPrice(BigDecimal.ONE); item.setLineTotal(BigDecimal.valueOf(index)); items.add(item);
        }
        pi.setItems(items); pi.setRemark("Shipping instructions must remain visible. ".repeat(100) + "REMARK-END");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(documents.excel(pi)))) {
            String text = content(book).replace("\n", "");
            for (int index = 1; index <= 50; index++) assertThat(text).contains("SKU-" + index, "END-" + index);
            assertThat(text).contains("REMARK-END");
            int numberedRows = 0;
            for (var row : book.getSheetAt(0)) {
                assertThat(row.getHeightInPoints()).isLessThanOrEqualTo(409);
                assertThat(row.getLastCellNum()).isLessThanOrEqualTo((short) 5);
                if (row.getCell(0) != null && row.getCell(0).getCellType() == CellType.NUMERIC) numberedRows++;
            }
            assertThat(numberedRows).isEqualTo(50);
        }
    }

    @Test void productPicturesArePreservedAndOtherSellersNeverReceiveTeewingBrand() throws Exception {
        var pi = PiDocumentServiceTest.sample(1); pi.setDocumentRenderVersion("PI_V4");
        var item = pi.getItems().getFirst(); item.setImageAttachmentId("product");
        byte[] product = PiImageServiceTest.picture(); var images = mock(PiImageService.class);
        when(images.bytes(item)).thenReturn(product); ReflectionTestUtils.setField(documents, "images", images);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(documents.excel(pi)))) {
            assertThat(book.getAllPictures()).hasSize(1);
            assertThat(book.getSheetAt(0).getDrawingPatriarch().getCTDrawing().getOneCellAnchorArray(0).getFrom().getRow()).isEqualTo(17);
        }
    }

    @Test void multilinePartyDetailsShiftTableInsteadOfDroppingAddressTail() throws Exception {
        var pi = referenceInvoice();
        var json = new ObjectMapper().findAndRegisterModules();
        var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(pi.getDocument());
        snapshot.put("sellerAddress", "Line\n".repeat(100) + "SELLER-END");
        snapshot.put("buyerAddress", "Line\n".repeat(80) + "BUYER-END");
        pi.setDocument(json.treeToValue(snapshot, PiDocumentSnapshot.class));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(documents.excel(pi)))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRepeatingRows().getFirstRow()).isGreaterThan(16);
            var partyText = new StringBuilder();
            for (int index = 6; index < sheet.getRepeatingRows().getFirstRow() - 6; index++) {
                var row = sheet.getRow(index);
                if (row != null) row.forEach(cell -> partyText.append(cell));
            }
            assertThat(partyText).contains("SELLER-END", "BUYER-END");
            sheet.forEach(row -> assertThat(row.getHeightInPoints()).isLessThanOrEqualTo(409));
        }
    }

    @Test void newExcelVersionPreservesCurrentPdfContent() throws Exception {
        var pi = referenceInvoice();
        String newText;
        try (var pdf = Loader.loadPDF(documents.pdf(pi))) { newText = new PDFTextStripper().getText(pdf); }
        pi.setDocumentRenderVersion("PI_V3");
        try (var pdf = Loader.loadPDF(documents.pdf(pi))) {
            assertThat(newText).isEqualTo(new PDFTextStripper().getText(pdf));
        }
    }

    @Test void archiveWorkerStoresNewLayoutFromSavedSnapshot(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var mapper = mock(PiExportMapper.class);
        var job = new PiExportJob(1, "CONFIRMATION", json.writeValueAsString(referenceInvoice()),
                "PROCESSING", 1, 1, "lease-token", null, null, null, null, null, null, null, null, null, null);
        when(mapper.next()).thenReturn(1L); when(mapper.claim(eq(1L), anyString())).thenReturn(1);
        when(mapper.get(1)).thenReturn(job);
        when(mapper.saveFile(eq(1L), anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(1);
        var transactions = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
        };
        try (var store = new com.bike.server.production.ProductionFileStore(directory.toString(), "local", "", "", "", "");
             var worker = new PiExportWorker(mapper, documents, store, json, transactions)) {
            assertThat(worker.processNext()).isTrue();
            verify(mapper).ready(1, "lease-token");
            verify(mapper, never()).fail(anyLong(), anyString(), anyString());
            var key = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(mapper).saveFile(eq(1L), eq("lease-token"), eq("xlsx"), key.capture(), anyString(), anyLong());
            try (var input = store.read(key.getValue()).getInputStream(); var book = new XSSFWorkbook(input)) {
                assertThat(book.getSheetAt(0).getSheetName()).isEqualTo("INVOICE");
                assertThat(book.getAllPictures()).hasSize(1);
                assertThat(numberFor(book, "Net total")).isEqualTo(435);
            }
        }
    }

    private static String content(XSSFWorkbook book) {
        var text = new StringBuilder(); book.getSheetAt(0).forEach(row -> row.forEach(cell -> text.append(cell).append('\n')));
        return text.toString();
    }

    private static double numberFor(XSSFWorkbook book, String label) {
        for (var row : book.getSheetAt(0)) if (row.getCell(0) != null && label.equals(row.getCell(0).toString())) return row.getCell(4).getNumericCellValue();
        throw new AssertionError("Missing total: " + label);
    }

    private static PiView referenceInvoice() {
        var pi = PiDocumentServiceTest.sample(1);
        pi.setDocumentRenderVersion("PI_V4"); pi.setPiNo("PREVIEW-PI-20261107");
        pi.setCustomerName("Sample Buyer"); pi.setCurrencyCode("HKD"); pi.setTradeType("EXPORT");
        pi.setSubtotal(new BigDecimal("435.00")); pi.setTotalAmount(new BigDecimal("435.00"));
        pi.setDiscountAmount(BigDecimal.ZERO); pi.setTaxAmount(BigDecimal.ZERO); pi.setFreightAmount(BigDecimal.ZERO);
        pi.setDocument(new PiDocumentSnapshot("Teewing Mobility Co., Limited", "Sample seller address, Hong Kong", null,
                "Sample buyer address, Indonesia", null, "Sample Bank", "SAMPLE-ACCOUNT", "Teewing Mobility Co., Limited",
                "Payment according to agreed terms.", "Freight to be arranged and paid by the buyer. Freight charges are not included in the invoice total.",
                "EXPORT", "1", LocalDate.of(2026, 11, 7), null, null, null, "FCA Shenzhen",
                "Indonesia", "Hong Kong", null, "SAMPLE-PHONE", "buyer@example.invalid", null,
                null, "SAMPLE-PHONE", "sales@example.invalid"));
        var item = pi.getItems().getFirst(); item.setProductName("Wireless Controller (Left) - BC100");
        item.setSpecification(null); item.setSkuCode("BC100"); item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("435.00")); item.setLineTotal(new BigDecimal("435.00"));
        item.setDiscountAmount(BigDecimal.ZERO); item.setTaxAmount(BigDecimal.ZERO);
        return pi;
    }
}
