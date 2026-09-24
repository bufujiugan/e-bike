package com.bike.server.pi;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PiReportMapper {
    List<Map<String,Object>> rows(@Param("queue") String queue,@Param("keyword") String keyword,@Param("offset") long offset,@Param("limit") long limit);
    long count(@Param("queue") String queue,@Param("keyword") String keyword);
}
