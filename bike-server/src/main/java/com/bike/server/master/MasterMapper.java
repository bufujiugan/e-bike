package com.bike.server.master;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MasterMapper {
    List<MasterItem> select(@Param("type") String type, @Param("keyword") String keyword);
    List<MasterItem> selectAuthorizedModels(@Param("agentId") long agentId, @Param("keyword") String keyword);
    MasterItem selectById(@Param("type") String type, @Param("id") long id);
    MasterItem selectByCode(@Param("type") String type, @Param("code") String code);
    int insert(@Param("type") String type, @Param("request") MasterCreateRequest request);
    int update(@Param("type") String type, @Param("id") long id, @Param("request") MasterUpdateRequest request);
    int updateStatus(@Param("type") String type, @Param("id") long id, @Param("enabled") boolean enabled);
    int insertLocation(@Param("warehouseId") long warehouseId, @Param("request") MasterCreateRequest request);
    LocationItem selectLocationById(@Param("id") long id);
    LocationItem selectLocationByCode(@Param("warehouseId") long warehouseId, @Param("code") String code);
    List<LocationItem> selectLocations(@Param("warehouseId") long warehouseId);
    int updateLocation(@Param("id") long id, @Param("request") LocationUpdateRequest request);
    int updateLocationStatus(@Param("id") long id, @Param("enabled") boolean enabled);
}
