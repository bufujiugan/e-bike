package com.bike.server.pi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;

public record PiConfirmationAttachment(String id,long piId,@JsonIgnore String requestId,@JsonIgnore String objectKey,
        String fileName,String contentType,long fileSize,String sha256,String visibility,Long createdBy,LocalDateTime createdAt) {}
