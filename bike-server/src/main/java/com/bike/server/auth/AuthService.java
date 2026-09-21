package com.bike.server.auth;

public interface AuthService {

    default LoginResponse login(LoginRequest request) { return login(request, null); }
    LoginResponse login(LoginRequest request, String clientIp);
    LoginResponse me(String username, String token);
    void logout(String token);
    AuthContextResponse context(String username);
    void changePassword(ChangePasswordRequest request);
}
