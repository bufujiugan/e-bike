package com.bike.server.part;

import com.bike.server.common.BusinessException;
import com.bike.server.common.BusinessUploadValidator;
import com.bike.server.files.FileUploadJournal;
import com.bike.server.production.ProductionFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

@Component
public class PartPhotoStore {
    private static final Logger LOG = LoggerFactory.getLogger(PartPhotoStore.class);
    private final ProductionFileStore files;
    private final BusinessUploadValidator validator;

    public PartPhotoStore(ProductionFileStore files, BusinessUploadValidator validator) {
        this.files = files;
        this.validator = validator;
    }

    public String save(long partId, PartPhotoUpload photo) {
        try (var prepared = validator.prepareImage(photo.fileName(), photo.contentType(), photo.size(), photo.source())) {
            String key = files.key(UUID.randomUUID() + "." + prepared.extension());
            try (var stream = Files.newInputStream(prepared.path())) {
                files.write(key, stream, prepared.size(), prepared.contentType(),
                        new FileUploadJournal.Context("PART_PHOTO", Long.toString(partId), prepared.fileName(), prepared.sha256()));
            }
            files.verify(key, prepared.size(), prepared.sha256());
            return key;
        } catch (BusinessException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            LOG.error("零部件图片存储失败 partId={}", partId, error);
            throw new BusinessException("PART_PHOTO_SAVE_FAILED", "零部件图片保存失败，请重试");
        }
    }

    public Resource read(StoredPhoto photo) {
        return new ByteArrayResource(BusinessUploadValidator.verifiedBytes(
                files.read(photo.objectKey()), photo.fileSize(), photo.sha256(), photo.contentType()));
    }

    public record StoredPhoto(String objectKey, String contentType, long fileSize, String sha256) {}
}
