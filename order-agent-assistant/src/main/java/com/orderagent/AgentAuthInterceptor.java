package com.orderagent;

import io.jsonwebtoken.JwtException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /query、/approve、/mcp 的登录校验：要求 Authorization: Bearer <order-system JWT>。
 * 解析出 userId 放进 AgentUserContext，同请求后续代码取用；请求结束清掉。
 * 挂在 /query、/approve、/mcp 上（见 WebConfig.addInterceptors）。
 *
 * 之前 agent 只认 sessionId、不知道调用者是谁——伪造/猜到 sessionId 就能冒用。
 * 这里把"人"钉在请求上，Controller 的会话归属校验、闸门的批准绑定才有依据。
 * /mcp 也要登录：query_order 是查订单（含收货信息），匿名访问等于把订单数据裸露。
 *
 * 黑名单（退出登录）校验：order-system 退出时在共享 Redis 写 TOKEN_VER:{userId} = 退出时间戳。
 * agent 和 order-system 连同一个 Redis，读同一条黑名单，判定规则与 order-system 的
 * LoginInterceptor 完全一致：token 签发时间(iat) 早于退出时间 → 旧 token，拒绝。
 * ——否则用户退出登录后，旧 JWT 在 agent 这层照样能用（agent 是把 userId 当身份往下游传的，
 * 不查这条黑名单就等于把"退出"这个动作在 agent 侧架空了）。
 */
@Component
public class AgentAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";
    /** 与 order-system JwtUtil.blacklistKey() 保持一致——同一条黑名单才能互通 */
    private static final String BLACKLIST_PREFIX = "TOKEN_VER:";

    private final AgentJwtService jwt;
    private final StringRedisTemplate redis;

    public AgentAuthInterceptor(AgentJwtService jwt, StringRedisTemplate redis) {
        this.jwt = jwt;
        this.redis = redis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS 预检 OPTIONS 不需要身份（真实请求才校验）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return unauthorized(response);
        }
        try {
            String token = header.substring(BEARER.length());
            Long userId = jwt.userId(token);
            if (isTokenRevoked(userId, jwt.issuedAtMillis(token))) {
                return unauthorized(response);
            }
            AgentUserContext.set(userId);
            return true;
        } catch (JwtException | NumberFormatException e) {
            return unauthorized(response);
        }
    }

    /**
     * 黑名单判定：TOKEN_VER:{userId} 存在且 token 签发时间早于退出时间戳 → 已失效。
     * 黑名单值读不出来/不是数字（比如 agent 和 order-system 的 Redis 不共享）→ 按未拉黑放行，
     * 避免数据格式不匹配把正常登录全打挂。
     */
    private boolean isTokenRevoked(Long userId, long issuedAtMillis) {
        String raw = redis.opsForValue().get(BLACKLIST_PREFIX + userId);
        if (raw == null) {
            return false;
        }
        try {
            return issuedAtMillis < Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AgentUserContext.clear();
    }

    private boolean unauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"未登录或登录已过期\"}");
        return false;
    }
}
