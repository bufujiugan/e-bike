package com.bike.server.pi;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PiTemplateMapper {
    List<PiTemplate> list(@Param("tradeType") String tradeType,@Param("offset") long offset,@Param("limit") long limit);
    long count(@Param("tradeType") String tradeType);
    PiTemplate find(@Param("code") String code,@Param("version") String version);
    int publish(@Param("request") PiTemplateRequest request,@Param("operatorId") Long operatorId);
}
