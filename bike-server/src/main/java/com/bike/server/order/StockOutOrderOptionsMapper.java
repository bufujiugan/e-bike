package com.bike.server.order;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockOutOrderOptionsMapper {
    List<StockOutOrderOption> selectPage(@Param("customerId") long customerId,
            @Param("keyword") String keyword, @Param("offset") long offset, @Param("limit") long limit);
    long count(@Param("customerId") long customerId, @Param("keyword") String keyword);
}
