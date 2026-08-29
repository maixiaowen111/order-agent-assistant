package com.orderagent;

import io.jsonwebtoken.JwtException;
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
 */
@Component
public class AgentAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final AgentJwtService jwt;

    public AgentAuthInterceptor(AgentJwtService jwt) {
        this.jwt = jwt;
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
            AgentUserContext.set(jwt.userId(header.substring(BEARER.length())));
            return true;
        } catch (JwtException | NumberFormatException e) {
            return unauthorized(response);
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
