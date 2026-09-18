package com.bike.server.production;

import com.bike.server.common.BusinessException;
import com.bike.server.rework.ReworkRequest;
import com.bike.server.validation.SnRuleService;
import org.springframework.stereotype.Component;

@Component
public class ProductionReworkGuard {
  private final ProductionGuard guard;
  private final ProductionMapper mapper;
  private final SnRuleService rules;

  public ProductionReworkGuard(
      ProductionGuard guard, ProductionMapper mapper, SnRuleService rules) {
    this.guard = guard;
    this.mapper = mapper;
    this.rules = rules;
  }

  public void beforeReplace(ReworkRequest request) {
    guard.lock();
    String code = rules.normalizePartSn(request.newPartSn(), request.partType());
    guard.requireCodeAvailable(code, null);
    var frame = mapper.selectFrame(rules.normalizeFrameSn(request.frameSn()));
    if (frame != null) guard.invalidatePendingLabels(ProductionServiceImpl.number(frame, "id"));
    var part = mapper.selectPart(code);
    if (part != null && !"PASSED".equals(part.get("qcStatus")))
      throw new BusinessException("PART_QC_FAILED", "新部件尚未质量放行");
  }
}
