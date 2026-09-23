package com.bike.server.master;

import org.apache.ibatis.annotations.*;

@Mapper
public interface CustomerProfileMapper {
    CustomerProfile get(@Param("id") long id);
    Long lockCustomer(@Param("id") long id);
    int insert(@Param("id") long id, @Param("r") CustomerProfileRequest request, @Param("userId") Long userId);
    int update(@Param("id") long id, @Param("r") CustomerProfileRequest request, @Param("userId") Long userId);
}
