package com.bike.server.agent;

import java.time.LocalDateTime;

public record PurchaseIntentView(Long id, Long agentId, String agentName, Long productModelId,
                                 String modelName, int intentQty, String status,
                                 LocalDateTime expireTime, String remark, LocalDateTime createdAt) {
}
