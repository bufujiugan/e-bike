package com.bike.server.order;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PaymentPriorityMapper {
    List<UnpaidInventoryCandidate> selectUnpaidCandidates(@Param("modelIds") List<Long> modelIds,
            @Param("paidPiId") long paidPiId);
    List<Long> selectDraftRevisions(@Param("piId") long piId);
    int updateCancelledOrder(@Param("orderId") long orderId, @Param("reason") String reason);
    int updateCancelledPi(@Param("piId") long piId, @Param("reason") String reason);
    int updateReleasedLock(@Param("lockId") long lockId, @Param("reason") String reason);
}
