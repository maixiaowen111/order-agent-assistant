package com.example.order.interceptor;

import com.example.order.context.UserContext;
import com.example.order.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录拦截器 — 校验每个请求是否携带有效 Token
 *
 * 执行时机：请求进入 Controller 之前
 *   请求 → 拦截器 preHandle → Controller → 拦截器 afterCompletion
 *
 * 校验逻辑：
 *   ① 拿 Authorization Header
 *   ② 没有 → 401
 *   ③ 有 → 解析 JWT → 解析成功 → 存 UserContext → 放行
 *   ④ 解析失败（过期/伪造）→ 401
 *
 * 拦截器 vs 过滤器：
 *   拦截器是 Spring 的，能拿到 Spring Bean，能知道请求要进哪个 Controller
 *   过滤器是 Servlet 的，更底层，Spring 还没介入
 *   权限校验用拦截器更合适——失败时可以返回 Result 格式的 JSON
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Controller 执行前调用
     *
     * @return true=放行  false=拦截（请求不会到达 Controller）
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // 0. 公开接口豁免：GET /api/product/{id} 商品详情——逛商城不登录也能看
        //    为什么不在 WebConfig.excludePathPatterns 写 "/api/product/*"？
        //      Spring 的 exclude 是 URL 通配符，不区分 HTTP 方法，会把管理员的
        //      PUT /api/product/{id}（编辑商品）也一起放行——放行的请求不解析 Token、
        //      UserContext 里没有 role，Controller 的 checkAdmin() 就会误报 403。
        //    所以豁免条件精确到「GET 方法 + 单级路径」，写操作照常走完整登录校验。
        String uri = request.getRequestURI();
        if ("GET".equalsIgnoreCase(request.getMethod())
                && uri.matches("/api/product/[^/]+")) {
            return true;
        }

        // 1. 从 Header 拿 Token
        String token = request.getHeader("Authorization");

        if (!StringUtils.hasText(token)) {
            sendUnauthorized(response, "请先登录");
            return false;
        }

        // 2. 去掉 "Bearer " 前缀（前端一般传 "Bearer eyJhbG..."）
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        // 3. 解析 Token
        try {
            Claims claims = JwtUtil.parse(token);
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);

            // 4. 黑名单检查：Token 签发时间 < 黑名单记录时间 = 已失效
            //    场景：用户退出登录 / 修改密码后，之前签发的所有 Token 都不再有效
            Long blacklistTime = (Long) redisTemplate.opsForValue()
                    .get(JwtUtil.blacklistKey(userId));
            if (blacklistTime != null) {
                long iat = claims.getIssuedAt().getTime();
                if (iat < blacklistTime) {
                    log.warn("Token 已被踢下线，userId={}", userId);
                    sendUnauthorized(response, "Token 已失效，请重新登录");
                    return false;
                }
            }

            // 5. 存入 ThreadLocal，后续 Service 直接取
            UserContext.set(userId, username, role);
            return true;

        } catch (JwtException e) {
            log.warn("Token 校验失败：{}", e.getMessage());
            sendUnauthorized(response, "Token 无效或已过期");
            return false;
        }
    }

    /**
     * 整个请求处理完后调用（即使抛异常也会执行）
     * 核心：清理 ThreadLocal，防止内存泄漏 + 数据串到下一个请求
     */
    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 返回 401 给前端
     */
    private void sendUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
    }
}
