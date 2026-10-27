package com.bike.server.common;

import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CustomerScopeMapper {
    Long lockReader(long id);
    Long readable(@Param("userId") long userId, @Param("customerId") long customerId);
    Long allowed(@Param("userId") long userId, @Param("customerId") long customerId);
    User lockUser(long id);
    User user(long id);
    List<Long> customers(long id);
    List<CustomerOption> assignedCustomers(long id);
    List<CustomerOption> customerOptions(@Param("keyword") String keyword,@Param("offset") long offset,@Param("limit") long limit);
    long customerCount(@Param("keyword") String keyword);
    List<UserOption> users(@Param("keyword") String keyword,@Param("offset") long offset,@Param("limit") long limit);
    long userCount(@Param("keyword") String keyword);
    Long customer(long id);
    int clear(long id);
    int grant(@Param("userId") long userId,@Param("customerId") long customerId,@Param("operator") Long operator);
    int advance(@Param("id") long id,@Param("revision") long revision);
    record User(long id,String userType,boolean enabled,long revision) {}
    record UserOption(long id,String username,String displayName,String userType,boolean enabled,LocalDateTime createdAt,LocalDateTime updatedAt) {}
    record CustomerOption(long id,String code,String name,String status) {}
}
