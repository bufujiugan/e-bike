package com.bike.server.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.xml.stream.*;
import org.apache.pdfbox.Loader;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** PRD 6.9 production/PI policy. After-sales keeps its own file policy. */
@Component
public class BusinessUploadValidator {
    public static final long IMAGE_LIMIT = 20L * 1024 * 1024;
    public static final long DOCUMENT_LIMIT = 50L * 1024 * 1024;
    public static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> DOCUMENTS = Set.of("pdf", "xlsx");

    public PreparedUpload prepare(MultipartFile file) throws IOException { return prepare(file, false); }
    public PreparedUpload prepareImage(MultipartFile file) throws IOException { return prepare(file, true); }

    private PreparedUpload prepare(MultipartFile file, boolean imagesOnly) throws IOException {
        String name = file == null ? null : file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 200 || !name.equals(name.trim())
                || name.chars().anyMatch(c -> Character.isISOControl(c) || "\\/:<>|?*\"".indexOf(c) >= 0))
            throw new BusinessException("ATTACHMENT_NAME_INVALID", "附件名称不合法，请使用普通文件名");
        int dot = name.lastIndexOf('.');
        String extension = dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!IMAGES.contains(extension) && (imagesOnly || !DOCUMENTS.contains(extension)))
            throw new BusinessException("ATTACHMENT_TYPE_UNSUPPORTED", imagesOnly ? "图片支持JPG、PNG、WebP" : "附件支持JPG、PNG、WebP、PDF、XLSX");
        long limit = IMAGES.contains(extension) ? IMAGE_LIMIT : DOCUMENT_LIMIT;
        if (file.isEmpty() || file.getSize() <= 0 || file.getSize() > limit) throw sizeError();
        Path temporary = Files.createTempFile("bike-business-", ".upload");
        try {
            MessageDigest digest = sha256();
            long actual = 0;
            try (var input = new DigestInputStream(file.getInputStream(), digest); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    actual += count;
                    if (actual > limit) throw sizeError();
                    output.write(buffer, 0, count);
                }
            }
            if (actual != file.getSize() || actual == 0) throw invalid("附件长度与内容不一致");
            String type = switch (extension) {
                case "pdf" -> { validatePdf(temporary); yield "application/pdf"; }
                case "xlsx" -> { validateXlsx(temporary); yield XLSX_TYPE; }
                default -> validateImage(temporary, extension);
            };
            String declared = file.getContentType();
            if (declared != null && !declared.isBlank()) {
                declared = declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!declared.equals(type) && !declared.equals("application/octet-stream")) throw invalid("附件声明类型与实际内容不一致");
            }
            return new PreparedUpload(temporary, name, extension, type, actual, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private static String validateImage(Path file, String extension) {
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("图片内容无法识别");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String expected = extension.equals("jpg") ? "jpeg" : extension;
                if (!format.equals(expected)) throw invalid("图片扩展名与实际格式不一致");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 12000 || height > 12000 || (long) width * height > 40_000_000)
                    throw invalid("图片最多4000万像素且单边不超过12000像素");
                reader.addIIOReadWarningListener((source, warning) -> { throw invalid("图片数据不完整"); });
                var decoded = reader.read(0);
                if (decoded == null) throw invalid("图片数据无法解码");
                decoded.flush();
                return "image/" + format;
            } finally { reader.dispose(); }
        } catch (BusinessException error) { throw error; }
        catch (IOException | RuntimeException error) { throw invalid("图片数据损坏"); }
    }

    private static void validatePdf(Path file) {
        try (var input = new RandomAccessFile(file.toFile(), "r")) {
            byte[] header = new byte[5]; input.readFully(header);
            if (!Arrays.equals(header, "%PDF-".getBytes(StandardCharsets.US_ASCII))) throw invalid("PDF文件头不合法");
            byte[] tail = new byte[(int) Math.min(input.length(), 1024)];
            input.seek(input.length() - tail.length); input.readFully(tail);
            if (!new String(tail, StandardCharsets.US_ASCII).contains("%%EOF")) throw invalid("PDF内容不完整");
            try (var document = Loader.loadPDF(file.toFile())) {
                if (document.isEncrypted() || document.getNumberOfPages() <= 0 || document.getNumberOfPages() > 500)
                    throw invalid("PDF须为未加密文件且不超过500页");
                for (var page : document.getPages()) if (page.getMediaBox() == null || page.getMediaBox().getWidth() <= 0 || page.getMediaBox().getHeight() <= 0)
                    throw invalid("PDF页面数据不合法");
            }
        } catch (BusinessException error) { throw error; }
        catch (IOException | RuntimeException error) { throw invalid("PDF文件无法解析"); }
    }

    private static void validateXlsx(Path file) {
        // Bound every expanded byte before POI opens the package. Never evaluate formulas or fetch external links.
        try (var zip = new ZipFile(file.toFile())) {
            if (zip.size() == 0 || zip.size() > 4096) throw invalid("XLSX压缩项数量不合法");
            long total = 0;
            Set<String> names = new HashSet<>();
            var entries = zip.entries();
            byte[] buffer = new byte[64 * 1024];
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); String name = entry.getName();
                if (!names.add(name) || name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains(".."))
                    throw invalid("XLSX压缩项名称不合法");
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("vbaproject") || lower.startsWith("xl/activex/") || lower.startsWith("xl/embeddings/"))
                    throw invalid("XLSX不能包含宏或嵌入可执行对象");
                long size = 0; var crc = new CRC32();
                try (var stream = zip.getInputStream(entry)) {
                    for (int count; (count = stream.read(buffer)) != -1;) {
                        size += count; total += count;
                        if (size > 100L * 1024 * 1024 || total > 200L * 1024 * 1024) throw invalid("XLSX解压后内容超过安全容量");
                        crc.update(buffer, 0, count);
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw invalid("XLSX压缩内容损坏");
                if (lower.endsWith(".xml") || lower.endsWith(".rels")) try (var stream = zip.getInputStream(entry)) { validateXml(stream); }
            }
            if (!names.contains("[Content_Types].xml") || !names.contains("_rels/.rels")) throw invalid("文件不是有效XLSX工作簿");
        } catch (BusinessException error) { throw error; }
        catch (Exception error) { throw invalid("XLSX文件无法解析"); }
        try (var pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            for (var part : pkg.getParts()) if (part.getContentType().toLowerCase(Locale.ROOT).contains("macroenabled"))
                throw invalid("XLSX不能包含宏");
            var reader = new XSSFReader(pkg);
            try (var workbook = reader.getWorkbookData()) { validateXml(workbook, Set.of("workbook")); }
            var sheets = reader.getSheetsData(); int count = 0;
            while (sheets.hasNext()) try (var sheet = sheets.next()) {
                if (++count > 500) throw invalid("XLSX最多500个工作表");
                validateXml(sheet, Set.of("worksheet", "chartsheet", "dialogsheet"));
            }
            if (count == 0) throw invalid("XLSX至少需要一个工作表");
        } catch (BusinessException error) { throw error; }
        catch (Exception error) { throw invalid("XLSX工作簿结构不合法"); }
    }

    private static void validateXml(InputStream stream) throws XMLStreamException {
        validateXml(stream, null);
    }
    private static void validateXml(InputStream stream, Set<String> expectedRoots) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> { throw new XMLStreamException("External entity denied"); });
        var xml = factory.createXMLStreamReader(stream); int depth = 0; boolean rootSeen = false;
        try {
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw invalid("XLSX不能引用外部XML实体");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 100) throw invalid("XLSX结构嵌套过深");
                    if (!rootSeen && expectedRoots != null && (!expectedRoots.contains(xml.getLocalName())
                            || !Set.of("http://schemas.openxmlformats.org/spreadsheetml/2006/main", "http://purl.oclc.org/ooxml/spreadsheetml/main").contains(Objects.toString(xml.getNamespaceURI(), ""))))
                        throw invalid("XLSX工作簿或工作表根元素不合法");
                    rootSeen = true;
                }
                if (event == XMLStreamConstants.END_ELEMENT) depth--;
            }
            if (!rootSeen) throw invalid("XLSX包含空XML文档");
        } finally { xml.close(); }
    }

    /** Validate before returning any bytes; bound allocation by trusted policy, not database metadata. */
    public static byte[] verifiedBytes(Resource resource, long size, String hash, String contentType) {
        long limit = Set.of("image/jpeg", "image/png", "image/webp").contains(Objects.toString(contentType, "")) ? IMAGE_LIMIT
                : Set.of("application/pdf", XLSX_TYPE).contains(Objects.toString(contentType, "")) ? DOCUMENT_LIMIT : 0;
        if (size <= 0 || size > limit || hash == null || !hash.matches("[0-9a-f]{64}")) throw integrity();
        try (var input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes((int) size + 1);
            if (bytes.length != size || !HexFormat.of().formatHex(sha256().digest(bytes)).equals(hash)) throw integrity();
            return bytes;
        } catch (IOException error) { throw new BusinessException("PI_ATTACHMENT_UNAVAILABLE", "附件读取失败，请检查存储"); }
    }

    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private static BusinessException invalid(String message) { return new BusinessException("ATTACHMENT_CONTENT_INVALID", message); }
    private static BusinessException sizeError() { return new BusinessException("ATTACHMENT_SIZE_INVALID", "附件不能为空；图片最大20MB，PDF/XLSX最大50MB"); }
    private static BusinessException integrity() { return new BusinessException("PI_ATTACHMENT_INTEGRITY_FAILED", "附件完整性校验失败，请检查备份"); }
    public record PreparedUpload(Path path, String fileName, String extension, String contentType, long size, String sha256) implements AutoCloseable {
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }
}
