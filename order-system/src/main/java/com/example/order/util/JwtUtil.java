package com.example.order.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类 — 生成 Token、解析 Token
 *
 * 核心概念：
 *   JWT（JSON Web Token）= 一段加密的字符串，包含用户信息 + 过期时间 + 签名
 *   服务端生成 Token 后发给客户端，客户端每次请求带这个 Token，
 *   服务端通过签名验证 Token 是否被篡改。
 *
 * 安全问题必须知道：
 *   ① secretKey 绝对不能泄露！生产环境放配置文件或环境变量
 *   ② 过期时间不能太长（攻击者拿到 Token 有操作窗口）
 *   ③ Token 存 localStorage 有 XSS 风险，生产环境用 httpOnly Cookie
 *   ④ 这个工具类只做生成和解析，不做权限判断——权限归拦截器管
 */
public class JwtUtil {

    // 密钥 — 生产环境绝对不能硬编码！至少 256 位
    // 当前学习阶段写死，阶段七改到配置文件
    private static final String SECRET = "MyOrderSystemSecretKeyForJwtToken2024!!!";

    // 过期时间：24 小时
    private static final long EXPIRE_MS = 24 * 60 * 60 * 1000;

    private static final SecretKey KEY =
            Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    /**
     * 生成 Token
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @return JWT 字符串，前端存起来，后续请求放 Header 里带回来
     */
    public static String generate(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))          // 存用户 ID
                .claim("username", username)
                .claim("role", role)                         // 用户角色（USER/ADMIN）
                .issuedAt(now)                             // 签发时间
                .expiration(new Date(now.getTime() + EXPIRE_MS))  // 过期时间
                .signWith(KEY)                             // 签名
                .compact();                                // 生成字符串
    }

    /**
     * 解析 Token，拿到里面的数据
     *
     * @param token JWT 字符串
     * @return Claims（类似 Map，getSubject() 拿用户 ID，get("key") 拿自定义字段）
     * @throws io.jsonwebtoken.JwtException Token 过期、签名不对、格式错误都会抛这个异常
     */
    public static Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 Token 中提取用户 ID
     */
    public static Long getUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    /**
     * 从 Token 中提取签发时间戳（毫秒）
     * 用途：Token 黑名单校验——签发时间早于黑名单时间 = Token 已被踢
     */
    public static long getIssuedAt(String token) {
        return parse(token).getIssuedAt().getTime();
    }

    /**
     * 黑名单 Redis Key
     * 格式：TOKEN_VER:{userId} = 该用户最后有效 Token 的签发时间戳
     * 用户退出/改密码时更新这个值，所有签发时间早于此值的 Token 全部失效
     */
    public static String blacklistKey(Long userId) {
        return "TOKEN_VER:" + userId;
    }
}
