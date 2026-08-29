package com.orderagent;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证「MCP 也要登录」真的挂上了：WebConfig 把鉴权拦截器同时挂到 /query、/approve、/mcp。
 * 防止有人以为 /mcp 是匿名入口、或以后重构把 /mcp 从拦截列表里漏掉。
 *
 * 为什么反射：Spring 不暴露"每个拦截器挂了哪些路径"——InterceptorRegistry.getInterceptors()
 * 是 protected，且 Spring 6.1 里它只返回裸拦截器（不含路径），InterceptorRegistration 也没有
 * includePatterns 的公开 getter。路径藏在私有字段里，只能反射读。
 * 字段名（registrations / includePatterns）在 Spring 6.x 稳定，变了测试会立刻报错提醒更新。
 */
class WebConfigTest {

    private static final String SECRET = "MyOrderSystemSecretKeyForJwtToken2024!!!";

    @Test
    void 鉴权拦截器挂在query_approve_mcp上() throws Exception {
        WebConfig config = new WebConfig(
                List.of("http://localhost:5173", "http://localhost:6274"),
                new AgentAuthInterceptor(new AgentJwtService(SECRET)));
        InterceptorRegistry registry = new InterceptorRegistry();
        config.addInterceptors(registry);

        List<String> patterns = registeredPaths(registry);

        assertThat(patterns).containsExactlyInAnyOrder("/query", "/approve", "/mcp");
    }

    /** 反射读出拦截器真实注册的 include patterns（Spring 没给公开 getter）。 */
    @SuppressWarnings("unchecked")
    private List<String> registeredPaths(InterceptorRegistry registry) throws Exception {
        Field registrationsField = InterceptorRegistry.class.getDeclaredField("registrations");
        registrationsField.setAccessible(true);
        List<InterceptorRegistration> registrations =
                (List<InterceptorRegistration>) registrationsField.get(registry);

        List<String> patterns = new ArrayList<>();
        for (InterceptorRegistration registration : registrations) {
            Field includeField = InterceptorRegistration.class.getDeclaredField("includePatterns");
            includeField.setAccessible(true);
            patterns.addAll((List<String>) includeField.get(registration));
        }
        return patterns;
    }
}
