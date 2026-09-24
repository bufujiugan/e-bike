package com.bike.server.pi;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PiReceiptAttachmentMapper {
    record Receipt(Long id, Long piId, String status) {}
    record Stored(String id, Long paymentId, String requestId, String objectKey, String fileName,
            String contentType, long fileSize, String sha256, Long createdBy, LocalDateTime createdAt) {
        PiReceiptAttachmentView view() { return new PiReceiptAttachmentView(id, paymentId, fileName, contentType, fileSize, sha256, createdBy, createdAt); }
    }
    Receipt selectReceipt(@Param("piId") long piId, @Param("paymentId") long paymentId);
    Long lockPi(long piId);
    Receipt lockReceipt(@Param("piId") long piId, @Param("paymentId") long paymentId);
    List<PiReceiptAttachmentView> selectAttachments(long paymentId);
    Stored selectByRequest(@Param("paymentId") long paymentId, @Param("requestId") String requestId);
    Stored selectStored(@Param("paymentId") long paymentId, @Param("id") String id);
    int insert(Stored row);
    long count(long paymentId);
}
