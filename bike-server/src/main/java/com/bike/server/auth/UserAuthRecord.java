package com.bike.server.auth;

public record UserAuthRecord(long id, String username, String passwordHash, String userType, Long agentId,
                             Long workstationId, boolean enabled, boolean mustChangePassword) {
    public UserAuthRecord(long id, String username, String passwordHash, String userType, Long agentId,
                          Long workstationId, boolean enabled) {
        this(id, username, passwordHash, userType, agentId, workstationId, enabled, false);
    }
}
