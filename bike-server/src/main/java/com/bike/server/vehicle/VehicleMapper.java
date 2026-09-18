package com.bike.server.vehicle;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDate;

@Mapper
public interface VehicleMapper {
    List<VehicleView> selectPage(@Param("keyword") String keyword, @Param("status") Integer status,
                                 @Param("modelId") Long modelId, @Param("customerId") Long customerId,
                                 @Param("orderNo") String orderNo, @Param("fromDate") LocalDate fromDate,
                                 @Param("toDate") LocalDate toDate, @Param("offset") long offset, @Param("limit") long limit);
    long count(@Param("keyword") String keyword, @Param("status") Integer status, @Param("modelId") Long modelId,
               @Param("customerId") Long customerId, @Param("orderNo") String orderNo,
               @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
    VehicleView selectById(@Param("id") long id);
    VehicleView selectByFrame(@Param("frameSn") String frameSn);
    VehicleView selectByFrameForUpdate(@Param("frameSn") String frameSn);
    VehicleView selectByQr(@Param("qrCode") String qrCode);
    int insert(VehicleCreateRequest request);
    int countEnabledModel(@Param("modelId") long modelId);
    int updateQr(@Param("id") long id, @Param("qrCode") String qrCode, @Param("token") String token, @Param("version") int version);
    int updateAssembled(@Param("id") long id, @Param("operatorId") Long operatorId,
                        @Param("qrCode") String qrCode, @Param("token") String token, @Param("version") int version);
    int insertOperationLog(@Param("businessId") String businessId, @Param("action") String action, @Param("operatorId") Long operatorId, @Param("operatorName") String operatorName, @Param("beforeData") String beforeData, @Param("afterData") String afterData, @Param("clientIp") String clientIp);
}
