package com.bike.server.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AgentModelAccessMapper {
    List<Long> selectModels(@Param("agentId") long agentId);
    int countAuthorized(@Param("agentId") long agentId,@Param("modelId") long modelId);
    Long lockAgent(@Param("agentId") long agentId);
    int countEnabledModels(@Param("ids") List<Long> ids);
    int disableAll(@Param("agentId") long agentId);
    int grant(@Param("agentId") long agentId,@Param("modelId") long modelId);
}
