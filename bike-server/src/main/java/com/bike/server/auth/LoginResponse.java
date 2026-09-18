package com.bike.server.auth;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, String username,
                            boolean mustChangePassword) {
    public LoginResponse(String accessToken, String tokenType, long expiresIn, String username) {
        this(accessToken, tokenType, expiresIn, username, false);
    }
}
