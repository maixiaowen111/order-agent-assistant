package com.orderagent;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 校验 order-system 签发的用户 JWT，取出 userId。
 * 密钥必须与 order-system 的 JwtUtil 一致（同一把钥匙才能验同一张签）。
 * 只做"验签取身份"，不做任何权限判断（权限归闸门和业务系统）。
 */
@Component
public class AgentJwtService {

    private final SecretKey key;

    public AgentJwtService(@Value("${agent.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析 token，返回 userId。
     * 签名不对/过期/格式错误都会抛 JwtException，由 AgentAuthInterceptor 统一转 401。
     */
    public Long userId(String token) {
        return Long.valueOf(claims(token).getSubject());
    }

    /** 返回 token 的签发时间（毫秒）。黑名单校验要用它和退出时间戳比对。 */
    public long issuedAtMillis(String token) {
        return claims(token).getIssuedAt().getTime();
    }

    private Claims claims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
