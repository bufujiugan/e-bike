package com.bike.server.pi;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PiFulfillmentProfileMapper {
    record StoredProfile(long id, String profileJson, boolean enabled, java.time.LocalDateTime createdAt) {}
    List<StoredProfile> listProfiles();
    StoredProfile selectProfile(@Param("id") long id);
    StoredProfile selectVersion(@Param("code") String code, @Param("version") String version);
    int insertProfile(@Param("r") PiFulfillmentProfileRequest request, @Param("json") String json, @Param("operatorId") Long operatorId);
    int updateEnabled(@Param("id") long id, @Param("enabled") boolean enabled);
    int countDocumentEvidence(@Param("piId") long piId, @Param("id") String id);
    int insertCreditSource(@Param("piId") long piId,@Param("skuId") long skuId,@Param("quantity") int quantity,@Param("lockNo") String lockNo,@Param("operatorId") Long operatorId);
    int insertCreditReservation(@Param("orderId") long orderId, @Param("lockId") long lockId, @Param("piId") long piId, @Param("operatorId") Long operatorId);
    int updateCreditReservation(@Param("orderId") long orderId, @Param("piId") long piId, @Param("operatorId") Long operatorId);
}
