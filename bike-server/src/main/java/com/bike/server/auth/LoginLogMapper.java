package com.bike.server.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LoginLogMapper {
    int insert(@Param("username") String username,
               @Param("success") boolean success,
               @Param("clientIp") String clientIp,
               @Param("failureReason") String failureReason);
}
