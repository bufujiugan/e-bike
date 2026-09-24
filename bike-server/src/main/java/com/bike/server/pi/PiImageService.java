package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import com.bike.server.production.ProductionFileStore;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

@Service
public class PiImageService {
    public record StoredImage(String id,String objectKey,String contentType,long fileSize,long skuId,String sha256) {}
    public record Snapshot(String attachmentId,String sha256) {}
    private final PiMapper mapper;
    private final ProductionFileStore files;
    public PiImageService(PiMapper mapper,ProductionFileStore files){this.mapper=mapper;this.files=files;}
    public static String contentType(byte[] bytes) {
        if(bytes.length>=8 && bytes[0]==(byte)0x89 && bytes[1]=='P' && bytes[2]=='N' && bytes[3]=='G')return "image/png";
        if(bytes.length>=3 && bytes[0]==(byte)0xff && bytes[1]==(byte)0xd8 && bytes[2]==(byte)0xff)return "image/jpeg";
        if(bytes.length>=12 && bytes[0]=='R' && bytes[1]=='I' && bytes[2]=='F' && bytes[3]=='F'
                && bytes[8]=='W' && bytes[9]=='E' && bytes[10]=='B' && bytes[11]=='P')return "image/webp";
        throw unavailable();
    }
    public Snapshot snapshot(long skuId,String requestedId) {
        var image=mapper.selectSkuImage(skuId,requestedId);
        if(image==null) {
            if(requestedId!=null&&!requestedId.isBlank())throw new BusinessException("PI_IMAGE_INVALID","选择的图片不属于该SKU");
            return new Snapshot(null,null);
        }
        return new Snapshot(image.id(),PiExportArchive.sha256(read(image)));
    }
    public byte[] bytes(PiItemView item) {
        if(item.getImageAttachmentId()==null)return null;
        var image=mapper.selectSkuImage(item.getSkuId(),item.getImageAttachmentId());
        if(image==null)throw unavailable();byte[] bytes=read(image);
        if(!PiExportArchive.sha256(bytes).equals(item.getImageSha256()))throw new BusinessException("PI_IMAGE_INTEGRITY_FAILED","PI图片与保存快照不一致，请核查存储");
        return bytes;
    }
    private byte[] read(StoredImage image) {
        if(image.fileSize()<=0||image.fileSize()>com.bike.server.common.BusinessUploadValidator.IMAGE_LIMIT||!Set.of("image/png","image/jpeg","image/webp").contains(image.contentType()))throw unavailable();
        try(var stream=files.read(image.objectKey()).getInputStream()) {
            byte[] bytes=stream.readNBytes((int)image.fileSize()+1);
            if(bytes.length!=image.fileSize())throw unavailable();
            if(!image.contentType().equals(contentType(bytes)))throw unavailable();
            if(image.sha256()!=null&&!PiExportArchive.sha256(bytes).equals(image.sha256()))throw new BusinessException("PI_IMAGE_INTEGRITY_FAILED","SKU图片与原上传内容不一致，请检查备份");
            try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw unavailable();var reader=readers.next();
                try{reader.setInput(input);if(reader.getWidth(0)<=0||reader.getHeight(0)<=0||reader.getWidth(0)>12000||reader.getHeight(0)>12000||(long)reader.getWidth(0)*reader.getHeight(0)>40_000_000)throw unavailable();}
                finally{reader.dispose();}
            }
            return bytes;
        }catch(IOException error){throw unavailable();}
    }
    private static BusinessException unavailable(){return new BusinessException("PI_IMAGE_UNAVAILABLE","PI商品图片不可读取或格式不合法，请检查原SKU附件");}
}
