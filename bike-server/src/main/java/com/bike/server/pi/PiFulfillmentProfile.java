package com.bike.server.pi;

import java.time.LocalDateTime;

public record PiFulfillmentProfile(long id, String profileCode, String version, String name,
        String tradeType, String collectionEntity, String templateCode, String templateVersion,
        PiFulfillmentRules rules, boolean enabled, LocalDateTime createdAt) {}
