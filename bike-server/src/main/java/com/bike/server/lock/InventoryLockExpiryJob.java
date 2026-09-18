package com.bike.server.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InventoryLockExpiryJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(InventoryLockExpiryJob.class);
    private final InventoryLockMapper mapper;

    public InventoryLockExpiryJob(InventoryLockMapper mapper) {
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${bike.inventory-lock.expiry-check-ms:60000}")
    @Transactional
    public void expireLocks() {
        var expired = mapper.selectExpiredForAudit();
        if (expired.isEmpty()) return;
        int updated = mapper.expireLocks();
        for (InventoryLockView lock : expired) {
            mapper.insertOperationLog(lock.getLockNo(), "库存锁定自动过期", null, "system", "到期时间:" + lock.getExpireTime(), null);
        }
        LOGGER.info("库存锁定自动过期完成，检测到={},更新={}", expired.size(), updated);
    }
}
