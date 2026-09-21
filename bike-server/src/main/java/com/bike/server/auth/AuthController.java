package com.bike.server.auth;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.security.core.Authentication;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success("登录成功", authService.login(request, httpRequest.getRemoteAddr()));
    }

    @GetMapping("/me")
    public ApiResponse<LoginResponse> me(Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success("查询成功", authService.me(authentication.getName(), request.getHeader("Authorization").substring(7)));
    }

    @GetMapping("/context")
    public ApiResponse<AuthContextResponse> context(Authentication authentication) {
        return ApiResponse.success("查询成功", authService.context(authentication.getName()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(request.getHeader("Authorization").substring(7));
        return ApiResponse.success("退出成功", null);
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ApiResponse.success("密码修改成功", null);
    }
}
