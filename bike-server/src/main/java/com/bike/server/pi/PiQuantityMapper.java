package com.bike.server.pi;

import org.apache.ibatis.annotations.*;

@Mapper
public interface PiQuantityMapper {
    java.util.List<PiRevisionSourceView> sources(@Param("piId") long piId);
    int clearSources(@Param("piId") long piId);
    int insertSource(@Param("piId") long piId, @Param("source") PiRevisionSourceView source);
    int convertedSource(@Param("piId") long piId, @Param("lockId") long lockId, @Param("orderId") long orderId);
    int resizeSku(@Param("orderId") long orderId, @Param("skuId") long skuId, @Param("quantity") int quantity);
    int resizeOrder(@Param("orderId") long orderId, @Param("quantity") int quantity);
    int resizeReservation(@Param("orderId") long orderId, @Param("quantity") int quantity);
    int closeSku(@Param("orderId") long orderId);
    int closeReservation(@Param("orderId") long orderId);
}
