package com.bike.server.pi;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
public record PiLogisticsAttachment(String id,long logisticsId,@JsonIgnore String requestId,@JsonIgnore String objectKey,
        String fileName,String contentType,long fileSize,String sha256,String visibility,Long createdBy,LocalDateTime createdAt) {}
