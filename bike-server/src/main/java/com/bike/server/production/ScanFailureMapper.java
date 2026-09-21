package com.bike.server.production;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScanFailureMapper {
  int insert(@Param("taskId") String taskId, @Param("code") String code,
      @Param("type") String type, @Param("operator") String operator,
      @Param("errorCode") String errorCode, @Param("reason") String reason);
  List<Map<String,Object>> select(@Param("keyword") String keyword, @Param("offset") long offset);
  long count(@Param("keyword") String keyword);
}
