package com.bike.server.auth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TokenRevocationCleanup {
    private final TokenRevocationMapper mapper;
    public TokenRevocationCleanup(TokenRevocationMapper mapper) { this.mapper = mapper; }
    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void cleanExpired() { mapper.deleteExpired(); }
}
