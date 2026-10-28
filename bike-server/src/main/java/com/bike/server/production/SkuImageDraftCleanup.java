package com.bike.server.production;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SkuImageDraftCleanup {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SkuImageDraftCleanup.class);
  private final ProductionMapper mapper;
  private final ProductionAttachmentService attachments;

  public SkuImageDraftCleanup(ProductionMapper mapper, ProductionAttachmentService attachments) {
    this.mapper = mapper;
    this.attachments = attachments;
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
  public void cleanup() {
    for (String draftToken : mapper.selectStaleSkuImageDrafts()) {
      try { attachments.cleanupStaleSkuDraft(draftToken); }
      catch (RuntimeException error) { LOG.warn("SKU图片草稿清理延后 draftToken={}", draftToken, error); }
    }
  }
}
