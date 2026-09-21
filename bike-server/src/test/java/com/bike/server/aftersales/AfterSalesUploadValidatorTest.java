package com.bike.server.aftersales;

import com.bike.server.common.BusinessException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static com.bike.server.aftersales.AfterSalesServiceTest.assertCode;

class AfterSalesUploadValidatorTest {
    @TempDir Path temp;
    final AfterSalesUploadValidator validator = new AfterSalesUploadValidator(new AfterSalesVideoValidator(8_294_400, 600, 72000));

    @Test void genuineImagesPdfVideoAndDiagnosticsValidateWithCorrectHashAndTemporaryCleanup() throws Exception {
        var fixtures = List.of(
            new MockMultipartFile("files", "故障.png", "image/png", AfterSalesTestFiles.image("png")),
            new MockMultipartFile("files", "vehicle.jpg", "image/jpeg", AfterSalesTestFiles.image("jpg")),
            new MockMultipartFile("files", "report.pdf", "application/pdf", AfterSalesTestFiles.pdf()),
            new MockMultipartFile("files", "fault.mp4", "video/mp4", AfterSalesTestFiles.video(temp)),
            new MockMultipartFile("files", "诊断.log", "text/plain", "电机测试\n错误 E01\t连接断开".getBytes(StandardCharsets.UTF_8)),
            new MockMultipartFile("files", "report.csv", "text/csv", "part,error\nmotor,E01".getBytes(StandardCharsets.UTF_8)),
            new MockMultipartFile("files", "report.json", "application/json", "{\"code\":\"E01\"}".getBytes(StandardCharsets.UTF_8)));
        for (var file : fixtures) {
            Path saved;
            try (var prepared = validator.prepare(file)) {
                saved = prepared.path();
                assertThat(Files.readAllBytes(saved)).isEqualTo(file.getBytes());
                assertThat(prepared.sha256()).isEqualTo(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(file.getBytes())));
                assertThat(prepared.fileName()).isEqualTo(file.getOriginalFilename());
            }
            assertThat(saved).doesNotExist();
        }
    }

    @Test void renamedFakeOrTruncatedFilesAndBinaryTextAreRejected() throws Exception {
        for (String name : List.of("image.png", "image.jpg", "document.pdf", "movie.mp4"))
            assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(new MockMultipartFile("files", name, "application/octet-stream", "not a real file".getBytes(StandardCharsets.UTF_8))));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(new MockMultipartFile("files", "cut.png", "image/png", Arrays.copyOf(AfterSalesTestFiles.image("png"), 33))));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(new MockMultipartFile("files", "wrong.jpg", "image/jpeg", AfterSalesTestFiles.image("png"))));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(new MockMultipartFile("files", "report.txt", "text/plain", new byte[]{'a', 0, 'b'})));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(new MockMultipartFile("files", "report.log", "text/plain", new byte[]{(byte) 0xc3, (byte) 0x28})));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(new MockMultipartFile("files", "report.json", "application/json", "{\"incomplete\":".getBytes())));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(new MockMultipartFile("files", "report.json", "application/json", "{} {}".getBytes())));
    }

    @Test void illegalPathsActiveContentAndMimeSpoofingAreRejected() throws Exception {
        for (String name : List.of("../proof.pdf", "C:\\proof.pdf", "bad\r\nname.pdf", "/etc/proof.pdf"))
            assertCode("ATTACHMENT_NAME_INVALID", () -> validator.prepare(new MockMultipartFile("files", name, "application/pdf", AfterSalesTestFiles.pdf())));
        for (String name : List.of("attack.html", "attack.svg", "attack.xml", "attack.exe", "archive.zip", ".pdf"))
            assertCode("ATTACHMENT_TYPE_UNSUPPORTED", () -> validator.prepare(new MockMultipartFile("files", name, "application/octet-stream", new byte[]{1})));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(new MockMultipartFile("files", "image.png", "text/html", AfterSalesTestFiles.image("png"))));
    }

    @Test void sizeAndDeclaredLengthLimitsAreEnforcedBeforeStorage() {
        assertCode("ATTACHMENT_SIZE_INVALID", () -> validator.prepare(new MockMultipartFile("files", "empty.log", "text/plain", new byte[0])));
        var huge = new MockMultipartFile("files", "large.pdf", "application/pdf", new byte[]{1}) {
            @Override public long getSize() { return AfterSalesUploadValidator.DOCUMENT_LIMIT + 1; }
        };
        assertCode("ATTACHMENT_SIZE_INVALID", () -> validator.prepare(huge));
        var misleading = new MockMultipartFile("files", "report.txt", "text/plain", "actual text".getBytes()) {
            @Override public long getSize() { return 1; }
        };
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(misleading));
    }

    @Test void videoMetadataLimitsAndForgedSampleCountsAreRejectedBeforeDecoding() throws Exception {
        byte[] valid = AfterSalesTestFiles.video(temp);
        var lowPixelValidator = new AfterSalesUploadValidator(new AfterSalesVideoValidator(100, 600, 72000));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> lowPixelValidator.prepare(new MockMultipartFile("files", "too-big.mp4", "video/mp4", valid)));
        var lowFrameValidator = new AfterSalesUploadValidator(new AfterSalesVideoValidator(8_294_400, 600, 1));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> lowFrameValidator.prepare(new MockMultipartFile("files", "too-many.mp4", "video/mp4", valid)));
        byte[] forged = valid.clone();
        int marker = indexOf(forged, "stsz".getBytes(StandardCharsets.US_ASCII));
        assertThat(marker).isPositive(); ByteBuffer.wrap(forged).putInt(marker + 12, Integer.MAX_VALUE);
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(new MockMultipartFile("files", "fake-index.mp4", "video/mp4", forged)));
        assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(new MockMultipartFile("files", "cut.mp4", "video/mp4", Arrays.copyOf(valid, valid.length / 2))));
    }

    @Test void hostileSpsHrdAndPpsFmoAllocationCountsAreRejectedByPreflight() throws Exception {
        byte[] valid = AfterSalesTestFiles.video(temp);
        int config = indexOf(valid, "avcC".getBytes(StandardCharsets.US_ASCII));
        int spsLength = Short.toUnsignedInt(ByteBuffer.wrap(valid).getShort(config + 10));
        byte[] genuineSps = Arrays.copyOfRange(valid, config + 12, config + 12 + spsLength);
        byte[] hostilePps = new NalBits(8).ue(0).ue(0).bits(0, 2).ue(1_000_000).finish();
        assertThatThrownBy(() -> validator.prepare(new MockMultipartFile("files", "pps.mp4", "video/mp4", parameterVideo(genuineSps, hostilePps))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("FMO");
        byte[] hostileSps = new NalBits(7).bits(66, 8).bits(0, 8).bits(10, 8).ue(0).ue(0).ue(2).ue(1)
            .bits(0, 1).ue(1).ue(1).bits(1, 1).bits(1, 1).bits(0, 1).bits(1, 1)
            .bits(0, 5).bits(1, 1).ue(1_000_000).finish();
        assertThatThrownBy(() -> validator.prepare(new MockMultipartFile("files", "hrd.mp4", "video/mp4", parameterVideo(hostileSps, hostilePps))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("HRD缓存数量");
    }

    @Test void inBandParameterSetsAreInspectedBeforeTheFirstFrameIsDecoded() throws Exception {
        byte[] valid = AfterSalesTestFiles.video(temp);
        int media = indexOf(valid, "mdat".getBytes(StandardCharsets.US_ASCII));
        int body = media + (ByteBuffer.wrap(valid).getInt(media - 4) == 1 ? 12 : 4);
        int sampleLength = ByteBuffer.wrap(valid).getInt(body);
        byte[] hostilePps = new NalBits(8).ue(0).ue(0).bits(0, 2).ue(1_000_000).finish();
        assertThat(sampleLength).isGreaterThanOrEqualTo(hostilePps.length);
        Arrays.fill(valid, body + 4, body + 4 + sampleLength, (byte) 0);
        System.arraycopy(hostilePps, 0, valid, body + 4, hostilePps.length);
        assertThatThrownBy(() -> validator.prepare(new MockMultipartFile("files", "in-band-pps.mp4", "video/mp4", valid)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("校验资源限制");
    }

    @Test void parserAllocationFailureIsContainedInChildJvmAndLaterVideosStillValidate() throws Exception {
        // This table is intentionally outside the manual preflight table set: containment must also
        // cover a JCodec parser that allocates from a count the parent does not understand.
        byte[] metadata = box("stps", ByteBuffer.allocate(8).putInt(0).putInt(Integer.MAX_VALUE).array());
        byte[] header = box("ftyp", new byte[]{'i', 's', 'o', 'm', 0, 0, 0, 0}), movie = box("moov", metadata), media = box("mdat", new byte[]{1});
        byte[] limited = ByteBuffer.allocate(header.length + movie.length + media.length).put(header).put(movie).put(media).array();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () ->
            assertCode("ATTACHMENT_CONTENT_INVALID", () -> validator.prepare(new MockMultipartFile("files", "resource-limit.mp4", "video/mp4", limited))));
        // The parent remains healthy and the concurrency permit has been released.
        try (var prepared = validator.prepare(new MockMultipartFile("files", "valid-after-rejection.mp4", "video/mp4", AfterSalesTestFiles.video(temp)))) {
            assertThat(prepared.contentType()).isEqualTo("video/mp4");
        }
    }

    private static byte[] parameterVideo(byte[] sps, byte[] pps) {
        var config = ByteBuffer.allocate(11 + sps.length + pps.length);
        config.put(new byte[]{1, 66, 0, 10, (byte) 255, (byte) 225}).putShort((short) sps.length).put(sps).put((byte) 1).putShort((short) pps.length).put(pps);
        byte[] header = box("ftyp", new byte[]{'i', 's', 'o', 'm', 0, 0, 0, 0}), moov = box("moov", box("avcC", config.array())), media = box("mdat", new byte[]{1});
        return ByteBuffer.allocate(header.length + moov.length + media.length).put(header).put(moov).put(media).array();
    }
    private static byte[] box(String type, byte[] body) { return ByteBuffer.allocate(8 + body.length).putInt(8 + body.length).put(type.getBytes(StandardCharsets.US_ASCII)).put(body).array(); }
    private static final class NalBits {
        final int type; final StringBuilder values = new StringBuilder();
        NalBits(int type) { this.type = type; }
        NalBits bits(int value, int count) { for (int i = count - 1; i >= 0; i--) values.append((value >>> i) & 1); return this; }
        NalBits ue(int value) { String binary = Integer.toBinaryString(value + 1); values.append("0".repeat(binary.length() - 1)).append(binary); return this; }
        byte[] finish() {
            values.append('1'); while (values.length() % 8 != 0) values.append('0');
            var result = new java.io.ByteArrayOutputStream(); result.write(0x60 | type); int zeros = 0;
            for (int i = 0; i < values.length(); i += 8) {
                int value = Integer.parseInt(values.substring(i, i + 8), 2);
                if (zeros >= 2 && value <= 3) { result.write(3); zeros = 0; }
                result.write(value); zeros = value == 0 ? zeros + 1 : 0;
            }
            return result.toByteArray();
        }
    }
    private static int indexOf(byte[] bytes, byte[] needle) {
        outer: for (int i = 0; i <= bytes.length - needle.length; i++) { for (int j = 0; j < needle.length; j++) if (bytes[i + j] != needle[j]) continue outer; return i; } return -1;
    }
}
