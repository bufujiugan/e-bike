package com.bike.server.common;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

public class BusinessUploadValidatorTest {
    final BusinessUploadValidator validator = new BusinessUploadValidator();
    public static byte[] webp() throws IOException { try (var stream = BusinessUploadValidatorTest.class.getResourceAsStream("/attachments/valid.webp")) { return stream.readAllBytes(); } }
    public static byte[] xlsx() throws IOException {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            book.createSheet("附件").createRow(0).createCell(0).setCellValue("银行到账核对"); book.write(output); return output.toByteArray();
        }
    }
    public static byte[] png() throws IOException {
        var output = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output); return output.toByteArray();
    }
    @Test void realWebpAndXlsxAreDecodedAndSpoofedOrTruncatedVariantsFail() throws Exception {
        for (var file : List.of(new MockMultipartFile("file","图片.webp","image/webp",webp()), new MockMultipartFile("file","回单.xlsx",BusinessUploadValidator.XLSX_TYPE,xlsx()))) {
            Path path;
            try (var prepared = validator.prepare(file)) {
                path=prepared.path(); assertThat(Files.readAllBytes(path)).isEqualTo(file.getBytes()); assertThat(prepared.sha256()).hasSize(64);
                assertThat(BusinessUploadValidator.verifiedBytes(new ByteArrayResource(file.getBytes()),prepared.size(),prepared.sha256(),prepared.contentType())).isEqualTo(file.getBytes());
            }
            assertThat(path).doesNotExist();
            invalid(() -> validator.prepare(new MockMultipartFile("file",file.getOriginalFilename(),file.getContentType(),Arrays.copyOf(file.getBytes(),12))));
        }
        invalid(() -> validator.prepare(new MockMultipartFile("file","fake.webp","image/webp",png())));
        invalid(() -> validator.prepare(new MockMultipartFile("file","fake.xlsx",BusinessUploadValidator.XLSX_TYPE,"not an office file".getBytes())));
        invalid(() -> validator.prepare(new MockMultipartFile("file","wrong.webp","application/pdf",webp())));
        assertThatThrownBy(()->validator.prepareImage(new MockMultipartFile("file","proof.xlsx",BusinessUploadValidator.XLSX_TYPE,xlsx()))).hasMessageContaining("图片支持");
    }
    @Test void exactTwentyMbImageAndFiftyMbPdfPassButNextByteFails() throws Exception {
        byte[] small=png();var picture=new ByteArrayOutputStream(); picture.write(small,0,small.length-12);
        int count=(int)BusinessUploadValidator.IMAGE_LIMIT-small.length-12;
        try(var chunk=new DataOutputStream(picture)) {
            byte[] type="biKe".getBytes(StandardCharsets.US_ASCII), data=new byte[count];var crc=new CRC32();crc.update(type);crc.update(data);
            chunk.writeInt(count);chunk.write(type);chunk.write(data);chunk.writeInt((int)crc.getValue());chunk.write(small,small.length-12,12);
        }
        byte[] image=picture.toByteArray();assertThat(image).hasSize((int)BusinessUploadValidator.IMAGE_LIMIT);
        try(var prepared=validator.prepare(new MockMultipartFile("file","20MB.png","image/png",image))) { assertThat(prepared.size()).isEqualTo(BusinessUploadValidator.IMAGE_LIMIT); }
        byte[] pdf;
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) { doc.addPage(new PDPage()); doc.save(out); pdf=out.toByteArray(); }
        byte[] document=Arrays.copyOf(pdf,(int)BusinessUploadValidator.DOCUMENT_LIMIT);Arrays.fill(document,pdf.length,document.length,(byte)' ');
        byte[] eof="\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);System.arraycopy(eof,0,document,document.length-eof.length,eof.length);
        try(var prepared=validator.prepare(new MockMultipartFile("file","50MB.pdf","application/pdf",document))) { assertThat(prepared.size()).isEqualTo(BusinessUploadValidator.DOCUMENT_LIMIT); }
        for(var file:List.of(new MockMultipartFile("file","large.png","image/png",Arrays.copyOf(image,image.length+1)),new MockMultipartFile("file","large.pdf","application/pdf",Arrays.copyOf(document,document.length+1)))) {
            assertThatThrownBy(()->validator.prepare(file)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("ATTACHMENT_SIZE_INVALID"));
        }
        invalid(()->validator.prepare(new MockMultipartFile("file","size.png","image/png",image) { @Override public long getSize(){return 1;} }));
    }
    @Test void xlsxRejectsZipBombExternalEntitiesMacroAndBrokenRelationships() throws Exception {
        byte[] valid=xlsx();
        for(var entry:List.of(Map.entry("xl/worksheets/sheet1.xml","<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///should-not-be-read'>]><worksheet>&a;</worksheet>".getBytes()),
                Map.entry("xl/vbaProject.bin",new byte[]{1,2}),Map.entry("xl/workbook.xml","<broken>".getBytes()),
                Map.entry("xl/worksheets/sheet1.xml","<unrelated/>".getBytes()),Map.entry("xl/huge.bin",new byte[101*1024*1024]))) {
            byte[] changed=repack(valid,entry.getKey(),entry.getValue());
            invalid(()->validator.prepare(new MockMultipartFile("file","bad.xlsx",BusinessUploadValidator.XLSX_TYPE,changed)));
        }
    }
    @Test void digestLengthAndTypeTamperingCannotReachDownload() throws Exception {
        var bytes=png();String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        for(var pair:List.of(Map.entry((long)bytes.length,"0".repeat(64)),Map.entry((long)bytes.length-1,hash),Map.entry(Long.MAX_VALUE,hash)))
            assertThatThrownBy(()->BusinessUploadValidator.verifiedBytes(new ByteArrayResource(bytes),pair.getKey(),pair.getValue(),"image/png")).hasMessageContaining("完整性");
        assertThatThrownBy(()->BusinessUploadValidator.verifiedBytes(new ByteArrayResource(bytes),bytes.length,hash,"text/html")).hasMessageContaining("完整性");
    }
    @Test void batchBoundariesAndPerItemFailureLeaveSuccessfulResultsRetryable() throws Exception {
        var file=new MockMultipartFile("files","proof.png","image/png",png());var request=new MockMultipartHttpServletRequest();request.addFile(file);request.addFile(file);
        var results=AttachmentBatch.upload(List.of("one","two"),List.of(file,file),request,(id,f)->{if(id.equals("two"))throw new BusinessException("TEST_FAILURE","单项失败");return id;});
        assertThat(results).extracting(AttachmentBatch.Item::success).containsExactly(true,false);
        assertThat(results.get(1).code()).isEqualTo("TEST_FAILURE");
        assertThatThrownBy(()->AttachmentBatch.upload(List.of("one","one"),List.of(file,file),request,(id,f)->id)).hasMessageContaining("不重复");
        var large=new MockMultipartHttpServletRequest();var ids=new ArrayList<String>();var files=new ArrayList<org.springframework.web.multipart.MultipartFile>();
        for(int n=0;n<20;n++){large.addFile(file);files.add(file);ids.add("item"+n);}
        assertThat(AttachmentBatch.upload(ids,files,large,(id,f)->id)).hasSize(20);
        large.addFile(file);files.add(file);ids.add("item20");assertThatThrownBy(()->AttachmentBatch.upload(ids,files,large,(id,f)->id)).hasMessageContaining("1至20");
    }
    private static byte[] repack(byte[] original,String name,byte[] replacement)throws IOException {
        var output=new ByteArrayOutputStream();try(var in=new ZipInputStream(new ByteArrayInputStream(original));var out=new ZipOutputStream(output)) {
            for(ZipEntry entry;(entry=in.getNextEntry())!=null;) {if(entry.getName().equals(name))continue;out.putNextEntry(new ZipEntry(entry.getName()));in.transferTo(out);out.closeEntry();}
            out.putNextEntry(new ZipEntry(name));out.write(replacement);out.closeEntry();
        }return output.toByteArray();
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void invalid(Checked call) {assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("ATTACHMENT_CONTENT_INVALID"));}
}
