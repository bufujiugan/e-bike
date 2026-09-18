package com.bike.server.master;

import java.time.LocalDateTime;

public record MasterItem(Long id, String code, String name, String contactName, String contactPhone, String status, LocalDateTime updatedAt) {
}
