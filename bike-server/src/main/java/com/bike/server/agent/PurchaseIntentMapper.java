package com.bike.server.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PurchaseIntentMapper {
    List<PurchaseIntentView> selectPage(@Param("agentId") Long agentId, @Param("offset") long offset, @Param("limit") long limit);
    long count(@Param("agentId") Long agentId);
    int countAuthorizedModel(@Param("agentId") long agentId, @Param("modelId") long modelId);
    int insert(@Param("request") PurchaseIntentCreateRequest request, @Param("agentId") long agentId, @Param("createdBy") Long createdBy);
    long selectLatestId(@Param("agentId") long agentId);
    PurchaseIntentView selectById(@Param("id") long id);
}
