package com.bike.server.aftersales;

import java.time.LocalDateTime;

public record AfterSalesMessageView(Long id, Long caseId, Long authorId, String visibility, String body, LocalDateTime createdAt) {}
