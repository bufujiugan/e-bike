package com.bike.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.bike.server.system.UserMapper;
import com.bike.server.auth.UserAuthRecord;
import java.util.ArrayList;
import java.util.List;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenService jwtTokenService;
    private final UserMapper userMapper;
    private final com.bike.server.auth.TokenRevocationMapper revocations;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService, UserMapper userMapper) {
        this(jwtTokenService, userMapper, null);
    }

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService, UserMapper userMapper,
                                   com.bike.server.auth.TokenRevocationMapper revocations) {
        this.jwtTokenService = jwtTokenService;
        this.userMapper = userMapper;
        this.revocations = revocations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var identity = jwtTokenService.parse(header.substring(7));
                String username = identity.username();
                UserAuthRecord user = userMapper == null ? null : userMapper.selectAuthByUsername(username);
                if (user == null || !user.enabled()) throw new IllegalStateException("用户不存在或已禁用");
                if (user.authVersion() != identity.authVersion()
                        || (revocations != null && revocations.countRevoked(identity.id()) > 0))
                    throw new IllegalStateException("登录凭证已撤销，请重新登录");
                if (user != null && user.mustChangePassword() && requiresPasswordChange(request)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"首次登录必须修改密码\",\"data\":null}");
                    return;
                }
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                List<String> roleCodes = userMapper == null ? List.of() : userMapper.selectRoleCodes(username);
                // 空角色或全部角色已停用必须撤权，不能按账号类型恢复管理权限。
                if (roleCodes.isEmpty()) throw new IllegalStateException("用户没有启用的角色");
                roleCodes.forEach(code -> authorities.add(new SimpleGrantedAuthority("ROLE_" + code)));
                // 数据范围和工位限制由账号类型兜底，避免自定义角色丢失隔离边界。
                if ("AGENT".equals(user.userType())) authorities.add(new SimpleGrantedAuthority("ROLE_WEB_AGENT"));
                if ("PDA_OPERATOR".equals(user.userType())) authorities.add(new SimpleGrantedAuthority("ROLE_PDA_OPERATOR"));
                if (userMapper != null) userMapper.selectPermissionCodes(username).forEach(code -> authorities.add(new SimpleGrantedAuthority("PERM_" + code)));
                if (userMapper != null) userMapper.selectWorkstationTypes(username).forEach(type -> authorities.add(new SimpleGrantedAuthority("WORKSTATION_" + type.toUpperCase(java.util.Locale.ROOT))));
                UsernamePasswordAuthenticationToken authentication =
                        UsernamePasswordAuthenticationToken.authenticated(username, null, authorities);
                authentication.setDetails(user);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException exception) {
                LOGGER.warn("JWT 认证失败，path={}", request.getRequestURI(), exception);
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean requiresPasswordChange(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/")) return false;
        String path = request.getRequestURI();
        return !("/api/auth/password".equals(path) || "/api/auth/context".equals(path)
                || "/api/auth/me".equals(path) || "/api/auth/logout".equals(path));
    }

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService) {
        this(jwtTokenService, null);
    }
}
