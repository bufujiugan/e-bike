package com.bike.server.lock;

import java.time.LocalDateTime;

/** 旧版转换数量没有订单占用关联时的只读对账行；不代表系统可以推断订单归属。 */
public record LegacyConversionAuditView(Long id, String lockNo, Long agentId, String agentName,
                                        Long productModelId, String modelName, int lockQty,
                                        int releasedQty, int convertedQty, String status,
                                        LocalDateTime expireTime, LocalDateTime createdAt,
                                        String remark) {}
