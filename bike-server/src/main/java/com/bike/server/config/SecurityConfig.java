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
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService jwtTokenService, com.bike.server.system.UserMapper userMapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, error) -> writeSecurityError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "请先登录"))
                        .accessDeniedHandler((request, response, error) -> writeSecurityError(response, HttpServletResponse.SC_FORBIDDEN, "PERMISSION_DENIED", "当前账号无权访问")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/login", "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/agent/**").hasAnyRole("WEB_AGENT", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/vehicles/**", "/api/parts/**", "/api/inventory/**", "/api/stock-in", "/api/stock-out").hasAnyRole("SUPER_ADMIN", "WEB_OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/master/**").hasAnyRole("SUPER_ADMIN", "WEB_OPERATOR", "WEB_AGENT", "PDA_OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAnyRole("SUPER_ADMIN", "WEB_OPERATOR", "WEB_AGENT", "PDA_OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/system-config/**", "/api/users/**", "/api/roles/**", "/api/workstations/**").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/parts", "/api/parts/import", "/api/vehicles", "/api/orders", "/api/orders/import", "/api/inventory-locks", "/api/master/**", "/api/users", "/api/roles", "/api/workstations").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/parts/**", "/api/master/**", "/api/users/**", "/api/roles/**", "/api/workstations/**", "/api/system-config/**").hasRole("SUPER_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenService, userMapper), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeSecurityError(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}");
    }
}
