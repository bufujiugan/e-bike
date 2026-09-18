package com.bike.server.system;
import org.apache.ibatis.annotations.Mapper;import org.apache.ibatis.annotations.Param;import java.util.List;
@Mapper public interface WorkstationMapper { List<WorkstationView> select(@Param("keyword")String keyword); WorkstationView selectByCode(@Param("code")String code); WorkstationView selectById(@Param("id")long id); int insert(@Param("request")WorkstationRequest request); int update(@Param("id")long id,@Param("request")WorkstationRequest request); int updateStatus(@Param("id")long id,@Param("enabled")boolean enabled); }
