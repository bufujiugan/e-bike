package com.bike.server.pi;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PiWorkflowMapper {
    List<PiWorkflowStepRecord> selectSteps(@Param("piId") long piId);
    List<PiWorkflowStepRecord> selectStepsForUpdate(@Param("piId") long piId);
    int initialize(@Param("piId") long piId);
    int inheritCompleted(@Param("piId") long piId, @Param("previousPiId") long previousPiId);
    int complete(@Param("piId") long piId, @Param("stepOrder") int stepOrder,
            @Param("operatorId") Long operatorId, @Param("note") String note,
            @Param("detailJson") String detailJson);
    int insertUpdate(@Param("piId") long piId, @Param("stepCode") String stepCode,
            @Param("detailJson") String detailJson, @Param("note") String note,
            @Param("operatorId") Long operatorId);
    List<PiWorkflowUpdateRecord> selectUpdates(@Param("piId") long piId);
}
