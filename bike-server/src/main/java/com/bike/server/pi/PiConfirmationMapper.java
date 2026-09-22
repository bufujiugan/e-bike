package com.bike.server.pi;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PiConfirmationMapper {
    List<PiConfirmationAttachment> list(@Param("piId") long piId,@Param("internal") boolean internal);
    PiConfirmationAttachment get(@Param("piId") long piId,@Param("id") String id);
    PiConfirmationAttachment byRequest(@Param("piId") long piId,@Param("requestId") String requestId);
    long count(@Param("piId") long piId);
    int insert(@Param("r") PiConfirmationAttachment row);
}
