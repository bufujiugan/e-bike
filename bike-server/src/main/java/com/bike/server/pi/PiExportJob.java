package com.bike.server.pi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;

public record PiExportJob(long piId,String sourceType,@JsonIgnore String snapshotJson,String status,
        long revision,int attempts,@JsonIgnore String claimToken,@JsonIgnore LocalDateTime leaseUntil,
        @JsonIgnore String pdfKey,String pdfSha256,Long pdfSize,@JsonIgnore String xlsxKey,
        String xlsxSha256,Long xlsxSize,String errorCode,LocalDateTime createdAt,LocalDateTime updatedAt) {}
