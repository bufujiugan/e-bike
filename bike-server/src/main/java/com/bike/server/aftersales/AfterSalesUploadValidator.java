package com.bike.server.aftersales;

import com.bike.server.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Checks actual bytes. Client MIME/filename are never treated as proof of file content. */
@Component
public class AfterSalesUploadValidator {
    static final long DOCUMENT_LIMIT = 10L * 1024 * 1024;
    static final long VIDEO_LIMIT = 50L * 1024 * 1024;
    static final long BATCH_LIMIT = 150L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "pdf", "mp4", "txt", "log", "csv", "json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AfterSalesVideoValidator videos;
    public AfterSalesUploadValidator(AfterSalesVideoValidator videos) { this.videos = videos; }

    public PreparedUpload prepare(MultipartFile file) throws IOException {
        String name = validateName(file == null ? null : file.getOriginalFilename());
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        long limit = extension.equals("mp4") ? VIDEO_LIMIT : DOCUMENT_LIMIT;
        if (file.isEmpty() || file.getSize() > limit) throw sizeError();
        Path temporary = Files.createTempFile("bike-after-sales-", ".upload");
        try {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            long actual = 0;
            try (var input = new DigestInputStream(file.getInputStream(), digest); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    actual += read;
                    if (actual > limit) throw sizeError();
                    output.write(buffer, 0, read);
                }
            }
            if (actual == 0 || actual != file.getSize()) throw new BusinessException("ATTACHMENT_CONTENT_INVALID", "附件长度与内容不一致");
            String type = switch (extension) {
                case "jpg", "jpeg", "png" -> validateImage(temporary, extension);
                case "pdf" -> { validatePdf(temporary); yield "application/pdf"; }
                case "mp4" -> { videos.validate(temporary); yield "video/mp4"; }
                case "json" -> { validateText(temporary, true); yield "application/json"; }
                case "csv" -> { validateText(temporary, false); yield "text/csv"; }
                default -> { validateText(temporary, false); yield "text/plain"; }
            };
            String declared = file.getContentType();
            if (declared != null && !declared.isBlank()) {
                declared = declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!declared.equals(type) && !declared.equals("application/octet-stream")
                    && !(type.equals("text/csv") && Set.of("text/plain", "application/vnd.ms-excel").contains(declared)))
                    throw new BusinessException("ATTACHMENT_CONTENT_INVALID", "附件声明类型与实际内容不一致");
            }
            return new PreparedUpload(temporary, name, extension, type, actual, HexFormat.of().formatHex(digest.digest()));
        } catch (Exception error) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    static String validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 200 || !name.equals(name.trim())
            || name.equals(".") || name.equals("..") || name.chars().anyMatch(c -> Character.isISOControl(c) || "\\/:<>|?*\"".indexOf(c) >= 0))
            throw new BusinessException("ATTACHMENT_NAME_INVALID", "附件名称不合法，请使用普通文件名");
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT)))
            throw new BusinessException("ATTACHMENT_TYPE_UNSUPPORTED", "支持 JPG、PNG、PDF、MP4(H.264)、TXT、LOG、CSV、JSON");
        return name;
    }

    private static String validateImage(Path file, String extension) throws IOException {
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("图片内容无法识别");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(extension.equals("png") ? format.equals("png") : format.equals("jpeg"))) throw invalid("图片扩展名与实际格式不一致");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 12000 || height > 12000 || (long) width * height > 40_000_000)
                    throw invalid("图片最多4000万像素且单边不超过12000像素");
                reader.addIIOReadWarningListener((source, warning) -> { throw invalid("图片数据不完整"); });
                var decoded = reader.read(0);
                if (decoded == null) throw invalid("图片数据无法解码");
                decoded.flush();
                return format.equals("png") ? "image/png" : "image/jpeg";
            } finally { reader.dispose(); }
        } catch (BusinessException error) { throw error; }
        catch (IOException | RuntimeException error) { throw invalid("图片数据损坏"); }
    }

    private static void validatePdf(Path file) throws IOException {
        try (var input = new RandomAccessFile(file.toFile(), "r")) {
            byte[] header = new byte[5]; input.readFully(header);
            if (!Arrays.equals(header, "%PDF-".getBytes(StandardCharsets.US_ASCII))) throw invalid("PDF文件头不合法");
            int tailLength = (int) Math.min(input.length(), 1024);
            byte[] tail = new byte[tailLength]; input.seek(input.length() - tailLength); input.readFully(tail);
            if (!new String(tail, StandardCharsets.US_ASCII).contains("%%EOF")) throw invalid("PDF内容不完整");
            try (var document = Loader.loadPDF(file.toFile())) {
                if (document.isEncrypted() || document.getNumberOfPages() <= 0 || document.getNumberOfPages() > 500)
                    throw invalid("PDF须为未加密文件且不超过500页");
                for (var page : document.getPages()) {
                    if (page.getMediaBox() == null || page.getMediaBox().getWidth() <= 0 || page.getMediaBox().getHeight() <= 0)
                        throw invalid("PDF页面数据不合法");
                }
            }
        } catch (BusinessException error) { throw error; }
        catch (IOException | RuntimeException error) { throw invalid("PDF文件无法解析"); }
    }

    private static void validateText(Path file, boolean json) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new InputStreamReader(Files.newInputStream(file), decoder)) {
            char[] buffer = new char[8192];
            for (int count; (count = reader.read(buffer)) != -1;) for (int i = 0; i < count; i++) {
                char c = buffer[i];
                if (Character.isISOControl(c) && c != '\t' && c != '\r' && c != '\n') throw invalid("诊断文本不能包含二进制控制字符");
            }
        } catch (CharacterCodingException error) { throw invalid("诊断文本必须采用有效UTF-8编码"); }
        if (json) {
            try (var parser = JSON.getFactory().createParser(file.toFile())) {
                if (JSON.readTree(parser) == null || parser.nextToken() != null) throw invalid("JSON诊断文件格式不正确");
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw invalid("JSON诊断文件格式不正确"); }
        }
    }

    private static BusinessException invalid(String message) { return new BusinessException("ATTACHMENT_CONTENT_INVALID", message); }
    private static BusinessException sizeError() { return new BusinessException("ATTACHMENT_SIZE_INVALID", "附件不能为空；视频最大50MB，其余文件最大10MB"); }
    public record PreparedUpload(Path path, String fileName, String extension, String contentType, long size, String sha256) implements AutoCloseable {
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }
}
