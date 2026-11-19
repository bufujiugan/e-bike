package com.bike.server.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderContractMapper {
    boolean existsPiForOrder(@Param("orderId") long orderId);
}
