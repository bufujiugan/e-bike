package com.bike.server.lock;

import com.bike.server.common.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class SkuLockBatchService {
    private final SkuLockMapper batches;

    public SkuLockBatchService(SkuLockMapper batches) {
        this.batches = batches;
    }

    // 写入前调用方须持有 ProductionGuard，避免并发转单或释放改动同批余额。
    public void requireWholeBatch(List<InventoryLockView> selected, Map<Long, Integer> quantities) {
        Long batchId = selected.stream().map(InventoryLockView::getLockRequestId)
                .filter(Objects::nonNull).findFirst().orElse(null);
        if (batchId == null) return;
        if (selected.stream().anyMatch(lock -> !batchId.equals(lock.getLockRequestId()))) {
            throw new BusinessException("LOCK_BATCH_MISMATCH", "请按同一次预锁提交分别转单");
        }
        for (var lock : batches.locks(batchId)) {
            if (!"LOCKED".equals(lock.getStatus()) || lock.getConvertedQty() != 0 || lock.getReleasedQty() != 0) {
                throw new BusinessException("INVENTORY_LOCK_CONFLICT", "本批预锁已转单或有锁定已失效，请查看关联订单或重新预锁");
            }
            if (!Objects.equals(quantities.get(lock.getId()), lock.getLockQty())) {
                throw new BusinessException("LOCK_BATCH_INCOMPLETE", "同一次预锁须按各 SKU 的预锁数量整批转入一个订单");
            }
        }
    }

    public void requireConvertibleBatch(InventoryLockView origin, List<InventoryLockView> available) {
        if (origin.getLockRequestId() == null) return;
        var batch = batches.locks(origin.getLockRequestId());
        if (available.size() != batch.size() || batch.stream().anyMatch(lock ->
                !"LOCKED".equals(lock.getStatus()) || lock.getConvertedQty() != 0 || lock.getReleasedQty() != 0)) {
            throw new BusinessException("INVENTORY_LOCK_CONFLICT", "本批预锁已转单或有锁定已失效，请查看关联订单或重新预锁");
        }
    }
}
