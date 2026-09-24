package com.bike.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService jwtTokenService, com.bike.server.system.UserMapper userMapper, com.bike.server.auth.TokenRevocationMapper revocations) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, error) -> writeSecurityError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "请先登录"))
                        .accessDeniedHandler((request, response, error) -> writeSecurityError(response, HttpServletResponse.SC_FORBIDDEN, "PERMISSION_DENIED", "当前账号无权访问")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/login", "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/agent/**").hasAnyRole("WEB_AGENT", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/parts", "/api/parts/import").hasAnyAuthority("ROLE_SUPER_ADMIN", "PERM_PART_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/parts/**").hasAnyAuthority("ROLE_SUPER_ADMIN", "PERM_PART_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/orders", "/api/orders/import").hasAnyAuthority("ROLE_SUPER_ADMIN", "PERM_ORDER_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/inventory-locks").hasAnyAuthority("ROLE_SUPER_ADMIN", "PERM_AGENT_LOCK_WRITE")
                        .requestMatchers("/api/users/*/customer-scope").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/master/customers/*/profile").authenticated()
                        // 功能权限和账号数据范围由 Service 检查，不能把可配置角色限制为固定角色名。
                        .requestMatchers(HttpMethod.GET, "/api/vehicles/**", "/api/parts/**", "/api/inventory/**", "/api/stock-in/**", "/api/stock-out/**", "/api/master/**", "/api/orders/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/system-config/**", "/api/users/**", "/api/roles/**", "/api/workstations/**").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/parts", "/api/parts/import", "/api/vehicles", "/api/orders", "/api/orders/import", "/api/inventory-locks", "/api/master/**", "/api/users", "/api/roles", "/api/workstations").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/parts/**", "/api/master/**", "/api/users/**", "/api/roles/**", "/api/workstations/**", "/api/system-config/**").hasRole("SUPER_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenService, userMapper, revocations), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeSecurityError(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}");
    }
}
