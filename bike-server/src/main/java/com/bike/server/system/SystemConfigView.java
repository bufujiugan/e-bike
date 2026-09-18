package com.bike.server.system;

import java.time.LocalDateTime;

public record SystemConfigView(String configKey, String configValue, String remark, LocalDateTime updatedAt) { }
