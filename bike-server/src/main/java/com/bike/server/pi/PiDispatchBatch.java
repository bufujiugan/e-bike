package com.bike.server.pi;

import java.time.LocalDateTime;
public record PiDispatchBatch(long id,String batchNo,long seriesId,Long executionPiId,long customerId,
        String sourceType,String status,int originalQty,int activeQty,LocalDateTime createdAt) {}
