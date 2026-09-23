package com.bike.server.master;

import java.time.LocalDateTime;

public record CustomerProfile(long customerId, String code, String name, String contactName, String contactPhone,
        String status, String address, String country, String email, String preferredCurrency,
        String businessOwner, long revision, LocalDateTime updatedAt) {}
