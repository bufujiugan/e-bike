package com.bike.server.order;

import java.time.LocalDateTime;

public record UnpaidInventoryCandidate(String kind, long id, LocalDateTime createdAt) {}
