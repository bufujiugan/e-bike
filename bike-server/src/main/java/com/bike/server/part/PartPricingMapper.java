package com.bike.server.part;

import com.bike.server.part.PartPricingService.Price;
import com.bike.server.part.PartPricingService.Update;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface PartPricingMapper {
    List<Price> selectPrices(@Param("keyword") String keyword, @Param("published") boolean published, @Param("offset") long offset);
    long countPrices(@Param("keyword") String keyword, @Param("published") boolean published);
    Long lockPart(@Param("id") long id);
    Price selectPrice(@Param("id") long id);
    int upsertPrice(@Param("id") long id, @Param("request") Update request);
}
