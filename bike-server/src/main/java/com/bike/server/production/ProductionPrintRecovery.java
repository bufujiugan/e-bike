package com.bike.server.production;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 未确认的打印不自动重投，避免重启或网络异常造成重复出纸。 */
@Service
public class ProductionPrintRecovery {
  private final ProductionMapper mapper;
  private final ProductionGuard guard;

  public ProductionPrintRecovery(ProductionMapper mapper, ProductionGuard guard) {
    this.mapper = mapper;
    this.guard = guard;
  }

  @Scheduled(fixedDelay = 60000)
  @Transactional
  public void recoverTimedOutClaims() {
    guard.lock();
    mapper.updateStalePrints();
  }
}
