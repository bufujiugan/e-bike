package com.bike.server.part;

import org.springframework.core.io.InputStreamSource;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;

public record PartPhotoUpload(String fileName, String contentType, long size, InputStreamSource source) {
    public static PartPhotoUpload from(MultipartFile file) {
        return file == null ? null : new PartPhotoUpload(file.getOriginalFilename(), file.getContentType(),
                file.getSize(), file);
    }

    public static PartPhotoUpload from(String fileName, byte[] bytes) {
        return new PartPhotoUpload(fileName, null, bytes.length, () -> new ByteArrayInputStream(bytes));
    }
}
