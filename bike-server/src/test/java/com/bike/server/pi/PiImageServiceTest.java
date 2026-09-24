package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bike.server.production.ProductionFileStore;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PiImageServiceTest {
    @TempDir Path directory;
    @Test void webpSourceDigestIsVerifiedAndRenderedIntoPdfAndWorkbook()throws Exception {
        var mapper=mock(PiMapper.class);byte[] image=com.bike.server.common.BusinessUploadValidatorTest.webp();
        try(var store=new ProductionFileStore(directory.toString(),"local","","","","")) {
            Files.write(directory.resolve("source.webp"),image);
            String hash=PiExportArchive.sha256(image);
            var row=new PiImageService.StoredImage("webp-image","local:source.webp","image/webp",image.length,1,hash);
            when(mapper.selectSkuImage(1,"webp-image")).thenReturn(row);
            var images=new PiImageService(mapper,store);var snapshot=images.snapshot(1,"webp-image");assertThat(snapshot.sha256()).isEqualTo(hash);
            var pi=PiDocumentServiceTest.sample(1);pi.setDocumentRenderVersion("PI_V3");
            var item=pi.getItems().getFirst();item.setImageAttachmentId(snapshot.attachmentId());item.setImageSha256(hash);
            item.setId(1L);var pis=mock(PiService.class);when(pis.get(1)).thenReturn(pi);var controller=new PiController(pis);
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"images",images);
            var response=controller.itemImage(1,1);assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/webp");assertThat(response.getBody()).isEqualTo(image);
            var documents=new PiDocumentService();org.springframework.test.util.ReflectionTestUtils.setField(documents,"images",images);
            try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(documents.excel(pi)))) {
                assertThat(book.getAllPictures()).hasSize(1);assertThat(ImageIO.read(new ByteArrayInputStream(book.getAllPictures().getFirst().getData()))).isNotNull();
            }
            try(var pdf=org.apache.pdfbox.Loader.loadPDF(documents.pdf(pi))) {
                long count=0;for(var page:pdf.getPages())for(var name:page.getResources().getXObjectNames())if(page.getResources().isImageXObject(name))count++;
                assertThat(count).isEqualTo(1);new org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImage(0).flush();
            }
            byte[] corrupt=image.clone();corrupt[corrupt.length-1]^=1;Files.write(directory.resolve("source.webp"),corrupt);
            assertThatThrownBy(()->images.snapshot(1,"webp-image")).hasMessageContaining("原上传内容");
        }
    }
    @Test void savedImageIsBoundToSkuAndDetectsReplacement()throws Exception {
        var mapper=mock(PiMapper.class);
        try(var store=new ProductionFileStore(directory.toString(),"local","","","","")) {
            byte[] image=picture();Files.write(directory.resolve("original.png"),image);
            var row=new PiImageService.StoredImage("image-id","local:original.png","image/png",image.length,10,null);
            when(mapper.selectSkuImage(10,null)).thenReturn(row);when(mapper.selectSkuImage(10,"image-id")).thenReturn(row);
            var service=new PiImageService(mapper,store);var snapshot=service.snapshot(10,null);
            var item=new PiItemView();item.setSkuId(10L);item.setImageAttachmentId(snapshot.attachmentId());item.setImageSha256(snapshot.sha256());
            assertThat(service.bytes(item)).isEqualTo(image);assertThat(service.snapshot(11,null).attachmentId()).isNull();
            assertThatThrownBy(()->service.snapshot(11,"image-id")).hasMessageContaining("不属于");
            Files.write(directory.resolve("original.png"),new byte[]{1});assertThatThrownBy(()->service.bytes(item)).hasMessageContaining("不可读取");
        }
    }
    static byte[] picture()throws IOException {
        var image=new java.awt.image.BufferedImage(320,180,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try{g.setColor(new java.awt.Color(235,242,247));g.fillRect(0,0,320,180);g.setColor(new java.awt.Color(35,90,130));g.setStroke(new java.awt.BasicStroke(8));g.drawOval(35,80,65,65);g.drawOval(215,80,65,65);g.drawLine(67,112,150,40);g.drawLine(150,40,247,112);g.drawLine(67,112,247,112);g.drawString("PI TEST IMAGE",105,166);}finally{g.dispose();}
        var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();
    }
}
