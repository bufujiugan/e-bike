package com.bike.server.pi;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PiLogisticsMapper {
    List<PiLogisticsView> list(@Param("piId") long piId);
    PiLogisticsView get(@Param("id") long id);
    int insert(@Param("piId") long piId,@Param("request") PiLogisticsRequest request,@Param("quantity") Integer quantity,@Param("operatorId") long operatorId);
    int update(@Param("id") long id,@Param("request") PiLogisticsRequest request,@Param("quantity") Integer quantity);
    int withdraw(@Param("id") long id,@Param("revision") int revision,@Param("reason") String reason);
    long insertedId();
    Request previous(@Param("piId") long piId,@Param("operatorId") long operatorId,@Param("requestId") String requestId);
    int saveRequest(@Param("piId") long piId,@Param("operatorId") long operatorId,@Param("requestId") String requestId,@Param("hash") String hash,@Param("id") long id);
    int saveHistory(@Param("id") long id,@Param("revision") int revision,@Param("action") String action,@Param("snapshot") String snapshot,@Param("operatorId") long operatorId);
    List<Map<String,Object>> history(@Param("id") long id);
    long otherForBatch(@Param("batchId") long batchId,@Param("id") long id);
    long shippedForStock(@Param("stockOutId") long stockOutId);
    Trace trace(@Param("stockNo") String stockNo);
    record Request(long logisticsId,String payloadHash) {}
    record Trace(String carrier,String trackingNo,String status) {}
}
