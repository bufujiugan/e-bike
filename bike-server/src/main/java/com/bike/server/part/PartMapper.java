package com.bike.server.part;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PartMapper {
    List<PartView> selectPage(@Param("keyword") String keyword, @Param("partType") String partType,
                              @Param("status") String status, @Param("supplierId") Long supplierId, @Param("offset") long offset, @Param("limit") long limit);
    long count(@Param("keyword") String keyword, @Param("partType") String partType, @Param("status") String status, @Param("supplierId") Long supplierId);
    PartView selectById(@Param("id") long id);
    PartView selectBySn(@Param("partSn") String partSn);
    int countEnabledSupplier(@Param("supplierId") long supplierId);
    int insert(PartCreateRequest request);
    int update(@Param("id") long id, @Param("request") PartUpdateRequest request);
    int insertOperationLog(@Param("businessId") String businessId, @Param("action") String action, @Param("operatorId") Long operatorId, @Param("operatorName") String operatorName, @Param("beforeData") String beforeData, @Param("afterData") String afterData, @Param("clientIp") String clientIp);
}
