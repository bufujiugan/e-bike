package com.bike.server.pi;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PiDispatchMapper {
    int insert(@Param("piId") long piId,@Param("batchNo") String batchNo,@Param("requestId") String requestId,
            @Param("hash") String hash,@Param("type") String type,@Param("operatorId") long operatorId);
    Prior prior(@Param("piId") long piId,@Param("operatorId") long operatorId,@Param("requestId") String requestId);
    Long idByNo(@Param("batchNo") String batchNo);
    int attach(@Param("batchId") long batchId,@Param("stockNo") String stockNo);
    boolean matches(@Param("batchId") long batchId,@Param("piId") long piId);
    PiDispatchBatch get(@Param("id") long id);
    List<PiDispatchBatch> batches(@Param("piId") long piId);
    List<Map<String,Object>> vehicles(@Param("batchId") long batchId);
    List<Map<String,Object>> options(@Param("keyword") String keyword,@Param("offset") long offset);
    long countOptions(@Param("keyword") String keyword);
    Map<String,Object> warehouseHeader(@Param("piId") long piId);
    Trace trace(@Param("stockNo") String stockNo);
    record Prior(long id,String payloadHash) {}
    record Trace(String piNo,Integer executionRevision,String batchNo,String sourceType) {}
}
