package com.bike.server.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtTokenService {

    private final SecretKey signingKey;
    private final Duration tokenTtl;

    public JwtTokenService(
            @Value("${bike.security.jwt-secret:}") String secret,
            @Value("${bike.security.token-ttl:PT8H}") Duration tokenTtl) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("必须通过 BIKE_JWT_SECRET 配置 JWT 密钥");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenTtl = tokenTtl;
    }

    public Token issue(String username) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenTtl);
        String value = Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                // 显式固定算法，避免 JJWT 根据非标准长度密钥自动推断为 HS384，
                // 导致 39 字节的部署密钥在解析阶段触发 WeakKeyException。
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new Token(value, tokenTtl.toSeconds());
    }

    public String parseUsername(String token) {
        Claims claims = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
        return claims.getSubject();
    }

    public record Token(String value, long expiresInSeconds) {
    }
}
