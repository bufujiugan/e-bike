package com.bike.server.pi;

import com.bike.server.aftersales.AfterSalesUploadValidator;
import com.bike.server.aftersales.AfterSalesVideoValidator;
import com.bike.server.common.*;
import com.bike.server.production.ProductionFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PiReceiptAttachmentTest {
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    @Test void agentsPdaAndBusinessReadersCannotDiscoverOrWriteReceiptsEvenWithMisassignedPermissions() throws Exception {
        var mapper = mock(PiReceiptAttachmentMapper.class);
        var files = mock(ProductionFileStore.class);
        var validator = mock(PiReceiptFileValidator.class);
        var service = service(mapper, files, validator);
        for (var authorities : List.of(
                new String[]{"PERM_PI_READ"},
                new String[]{"PERM_PI_PAYMENT_CONFIRM"},
                new String[]{"ROLE_WEB_AGENT", "PERM_PI_READ", "PERM_PI_PAYMENT_CONFIRM", "ROLE_SUPER_ADMIN"},
                new String[]{"ROLE_PDA_OPERATOR", "PERM_PI_READ", "PERM_PI_RELEASE", "ROLE_SUPER_ADMIN"})) {
            PiServiceTest.login(authorities);
            assertThatThrownBy(() -> service.list(1, 10)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.download(1, 10, "attachment")).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.upload(1, 10, "request", file("proof.png", "image/png", image()))).isInstanceOf(BusinessException.class);
        }
        for (String type : List.of("AGENT", "PDA_OPERATOR")) {
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("misassigned", null,
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
            auth.setDetails(new com.bike.server.auth.UserAuthRecord(1, "misassigned", "", type, null, null, true));
            SecurityContextHolder.getContext().setAuthentication(auth);
            assertCode("PERMISSION_DENIED", () -> service.list(1, 10));
            assertCode("PERMISSION_DENIED", () -> service.download(1, 10, "attachment"));
            assertCode("PERMISSION_DENIED", () -> service.upload(1, 10, "request", file("proof.png", "image/png", image())));
        }
        verifyNoInteractions(mapper, files, validator);
    }

    @Test void releaseOnlyCanReadButCannotUploadAndLookupIsScopedToRealReceipt() throws Exception {
        var mapper = mock(PiReceiptAttachmentMapper.class);
        var files = mock(ProductionFileStore.class);
        var validator = mock(PiReceiptFileValidator.class);
        var service = service(mapper, files, validator);
        when(mapper.selectReceipt(1, 10)).thenReturn(new PiReceiptAttachmentMapper.Receipt(10L, 1L, "CONFIRMED"));
        when(mapper.selectAttachments(10)).thenReturn(List.of());
        PiServiceTest.login("PERM_PI_READ", "PERM_PI_RELEASE");
        assertThat(service.list(1, 10)).isEmpty();
        assertCode("PERMISSION_DENIED", () -> service.upload(1, 10, "request", file("proof.png", "image/png", image())));
        assertCode("ATTACHMENT_NOT_FOUND", () -> service.list(2, 10));
        assertCode("ATTACHMENT_NOT_FOUND", () -> service.download(1, 10, "unknown"));
        verifyNoInteractions(files, validator);
    }

    @Test void legacyVoucherObjectKeysCannotBeUsedToReadArbitraryStorage() throws Exception {
        var mapper = mock(PiReceiptAttachmentMapper.class);
        var files = mock(ProductionFileStore.class);
        var service = service(mapper, files, mock(PiReceiptFileValidator.class));
        PiServiceTest.login("PERM_PI_READ", "PERM_PI_PAYMENT_CONFIRM");
        when(mapper.selectReceipt(1, 10)).thenReturn(new PiReceiptAttachmentMapper.Receipt(10L, 1L, "CONFIRMED"));
        when(mapper.selectStored(10, "a")).thenReturn(new PiReceiptAttachmentMapper.Stored("a", 10L, "r", "local:../passwords", "x.pdf", "application/pdf", 10, "hash", null, LocalDateTime.now()));
        assertCode("ATTACHMENT_STORAGE_INVALID", () -> service.download(1, 10, "a"));
        verifyNoInteractions(files);
    }

    @Test void realImagesAndPdfUseSharedByteValidationAndNeverInvokeVideoProcessing() throws Exception {
        var video = mock(AfterSalesVideoValidator.class);
        var validator = new PiReceiptFileValidator(new AfterSalesUploadValidator(video));
        for (var upload : List.of(file("银行凭证.png", "image/png", image()), file("银行凭证.pdf", "application/pdf", pdf()))) {
            java.nio.file.Path path;
            try (var result = validator.prepare(upload)) {
                path = result.path();
                assertThat(java.nio.file.Files.readAllBytes(path)).isEqualTo(upload.getBytes());
                assertThat(result.sha256()).hasSize(64);
                assertThat(result.size()).isEqualTo(upload.getSize());
            }
            assertThat(path).doesNotExist();
        }
        verifyNoInteractions(video);
    }

    @Test void receiptAllowlistRejectsVideosSpoofedContentPathsAndOversizedStreams() throws Exception {
        var video = mock(AfterSalesVideoValidator.class);
        var validator = new PiReceiptFileValidator(new AfterSalesUploadValidator(video));
        assertCode("ATTACHMENT_TYPE_UNSUPPORTED", () -> validator.prepare(file("video.mp4", "video/mp4", "fake".getBytes())));
        assertCode("ATTACHMENT_NAME_INVALID", () -> validator.prepare(file("../proof.png", "image/png", image())));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(file("fake.png", "image/png", "fake".getBytes())));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(file("fake.pdf", "application/pdf", "%PDF-1.7\n%%EOF".getBytes())));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(file("proof.png", "application/pdf", image())));
        byte[] realPdf = pdf();
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(file("partial.pdf", "application/pdf", Arrays.copyOf(realPdf, 20))));
        assertCode("ATTACHMENT_SIZE_INVALID", () -> validator.prepare(new MockMultipartFile("file", "large.png", "image/png", new byte[1]) {
            @Override public long getSize() { return PiReceiptFileValidator.LIMIT + 1; }
        }));
        assertThatThrownBy(() -> validator.prepare(new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[(int) PiReceiptFileValidator.LIMIT + 1]) {
            @Override public long getSize() { return 1; }
        })).isInstanceOf(BusinessException.class);
        verifyNoInteractions(video);
    }

    @Test void downloadForcesPrivateAttachmentHeaders() {
        var service = mock(PiReceiptAttachmentService.class);
        when(service.download(1, 10, "file")).thenReturn(new PiReceiptAttachmentService.Download("到账.pdf", "application/pdf", 3, new ByteArrayResource(new byte[]{1, 2, 3})));
        var response = new PiReceiptAttachmentController(service).download(1, 10, "file");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
        assertThat(response.getHeaders().getContentDisposition().getType()).isEqualTo("attachment");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
    }

    @Test void multipartWithExtraOrMixedFilesIsRejectedBeforeAnyUpload() throws Exception {
        var service = mock(PiReceiptAttachmentService.class);
        var controller = new PiReceiptAttachmentController(service);
        var valid = file("valid.png", "image/png", image());
        for (String part : List.of("file", "extra")) {
            var request = new org.springframework.mock.web.MockMultipartHttpServletRequest();
            request.addFile(valid);
            request.addFile(new MockMultipartFile(part, "bad.pdf", "application/pdf", "fake".getBytes(StandardCharsets.UTF_8)));
            assertCode("INVALID_PARAMETER", () -> controller.upload(1, 10, "request", valid, request));
        }
        verifyNoInteractions(service);
    }

    static PiReceiptAttachmentService service(PiReceiptAttachmentMapper mapper, ProductionFileStore files, PiReceiptFileValidator validator) {
        return new PiReceiptAttachmentService(mapper, files, validator, mock(OperationLogMapper.class), new ObjectMapper().findAndRegisterModules());
    }
    static MockMultipartFile file(String name, String type, byte[] bytes) { return new MockMultipartFile("file", name, type, bytes); }
    static byte[] image() {
        try {
            var output = new ByteArrayOutputStream(); var picture = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            picture.setRGB(0, 0, 0x123456); ImageIO.write(picture, "png", output); return output.toByteArray();
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    static byte[] pdf() {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.save(output); return output.toByteArray();
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    static void assertCode(String code, Checked action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
