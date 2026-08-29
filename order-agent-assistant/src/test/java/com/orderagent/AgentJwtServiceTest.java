package com.orderagent;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 校验测试：同一把密钥签发 → 能取出 userId；换密钥伪造 / 乱写 token → 直接拒。
 * 完全离线（jjwt 自带的 HMAC），不依赖 order-system 服务在线。
 * 密钥必须和 order-system 的 JwtUtil 完全一致——同一把钥匙才能验同一张签。
 */
class AgentJwtServiceTest {

    private static final String SECRET = "MyOrderSystemSecretKeyForJwtToken2024!!!";
    private final AgentJwtService jwt = new AgentJwtService(SECRET);

    private String token(long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void 正确密钥签发的token_能取出userId() {
        assertThat(jwt.userId(token(42L))).isEqualTo(42L);
    }

    @Test
    void 能取出签发时间_供黑名单比对() {
        // JWT 的 iat 按秒存，毫秒会被截掉——取整秒时间戳，往返比对才精确
        long issuedAt = (System.currentTimeMillis() / 1000) * 1000 - 60_000;
        String t = Jwts.builder()
                .subject("42")
                .issuedAt(new Date(issuedAt))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(jwt.issuedAtMillis(t)).isEqualTo(issuedAt);
    }

    @Test
    void 换密钥签发的token_验签失败抛异常() {
        String forged = Jwts.builder()
                .subject("42")
                .signWith(Keys.hmacShaKeyFor(
                        "forge-key-for-testing-only-1234567890abcdef".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwt.userId(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void 乱写的token_抛异常() {
        assertThatThrownBy(() -> jwt.userId("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void 过期的token_抛异常() {
        String expired = Jwts.builder()
                .subject("42")
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwt.userId(expired)).isInstanceOf(JwtException.class);
    }
}
