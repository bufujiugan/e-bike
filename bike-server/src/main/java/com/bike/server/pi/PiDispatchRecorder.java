package com.bike.server.pi;

import com.bike.server.common.*;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Only invoked inside the inventory writer's existing transaction and mutex. */
@Component
public class PiDispatchRecorder {
    private final PiDispatchMapper mapper;
    public PiDispatchRecorder(PiDispatchMapper mapper){this.mapper=mapper;}
    public long begin(long piId,String requestId,String hash,String type) {
        String no="SHIP"+UUID.randomUUID().toString().replace("-","").toUpperCase();
        if(mapper.insert(piId,no,requestId,hash,type,CurrentOperator.id())!=1)
            throw new BusinessException("PI_REVISION_CHANGED","PI执行版本已变化，请刷新后核对");
        return mapper.idByNo(no);
    }
    public void requireBatch(long batchId,long piId) {
        if(!mapper.matches(batchId,piId))throw new BusinessException("PI_BATCH_MISMATCH","出库订单与批次不属于同一PI及修订");
    }
    public void attach(long batchId,String stockNo) {
        if(mapper.attach(batchId,stockNo)!=1)throw new BusinessException("PI_BATCH_FAILED","逐车出库与批次关联失败");
    }
}
