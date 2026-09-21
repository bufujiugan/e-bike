package com.bike.server.pi;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PiSeriesMapper {
    Long seriesId(@Param("piId") long piId);
    int insertSeries(@Param("piId") long piId);
    int assignSeries(@Param("piId") long piId);
    SeriesLock lockSeries(@Param("seriesId") long seriesId);
    int publishRevision(@Param("seriesId") long seriesId, @Param("piId") long piId,
            @Param("previousPiId") long previousPiId, @Param("revision") int revision);
    RevisionRequest revisionRequest(@Param("operatorId") long operatorId, @Param("requestId") String requestId);
    int saveRevisionRequest(@Param("seriesId") long seriesId, @Param("operatorId") long operatorId,
            @Param("requestId") String requestId, @Param("hash") String hash, @Param("piId") long piId);
    int link(@Param("piId") long piId, @Param("orderId") long orderId, @Param("type") String type);
    List<Long> orderIds(@Param("piId") long piId);
    List<PiFulfillmentLine> fulfillment(@Param("piId") long piId);
    Long currentPiForOrder(@Param("orderId") long orderId);
    List<java.util.Map<String,Object>> lockOptions(@Param("customerId") long customerId,
            @Param("agentId") Long agentId,@Param("keyword") String keyword,@Param("offset") long offset);
    long countLockOptions(@Param("customerId") long customerId,@Param("agentId") Long agentId,@Param("keyword") String keyword);
    List<PiShipmentGate> gates(@Param("orderId") long orderId);
    Integer remainingForVehicle(@Param("piId") long piId, @Param("vehicleId") long vehicleId);
    int holdAfterPayment(@Param("orderId") long orderId, @Param("piId") long piId,
            @Param("paidAmount") BigDecimal paidAmount, @Param("operatorId") Long operatorId);
    Conversion conversion(@Param("operatorId") long operatorId, @Param("requestId") String requestId);
    int saveConversion(@Param("operatorId") long operatorId, @Param("requestId") String requestId,
            @Param("hash") String hash, @Param("piId") long piId);
    record Conversion(long piId, String payloadHash) {}
    record SeriesLock(long id, long currentPiId, int nextRevision, int version) {}
    record RevisionRequest(long piId, String payloadHash) {}
}
