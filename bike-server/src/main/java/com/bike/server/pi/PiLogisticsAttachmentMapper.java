package com.bike.server.pi;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper
public interface PiLogisticsAttachmentMapper {
    List<PiLogisticsAttachment> list(@Param("logisticsId") long logisticsId,@Param("internal") boolean internal);
    PiLogisticsAttachment get(@Param("logisticsId") long logisticsId,@Param("id") String id);
    PiLogisticsAttachment byRequest(@Param("logisticsId") long logisticsId,@Param("requestId") String requestId);
    long count(@Param("logisticsId") long logisticsId);
    int insert(@Param("r") PiLogisticsAttachment row);
    String lockLogistics(@Param("logisticsId") long logisticsId);
}
