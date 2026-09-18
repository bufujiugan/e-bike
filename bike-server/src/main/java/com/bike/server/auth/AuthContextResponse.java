package com.bike.server.auth;

import java.util.List;

public record AuthContextResponse(String username, String userType, Long agentId,
                                  List<String> roles, List<String> permissions, List<String> workstations,
                                  boolean mustChangePassword) {
    public AuthContextResponse(String username, String userType, Long agentId,
                               List<String> roles, List<String> permissions, List<String> workstations) {
        this(username, userType, agentId, roles, permissions, workstations, false);
    }
}
