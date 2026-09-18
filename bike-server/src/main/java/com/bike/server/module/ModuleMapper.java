package com.bike.server.module;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ModuleMapper {
    List<Map<String, Object>> selectRows(@Param("module") String module, @Param("agentId") Long agentId);
}
