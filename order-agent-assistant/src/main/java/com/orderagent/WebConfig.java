package com.orderagent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * ① CORS：只给 /mcp 开跨域（MCP Inspector 是浏览器应用会跨域；Claude Desktop / Cursor
 *    是 Electron，本身不强制 CORS）。允许的来源从配置读（agent.cors.allowed-origins），
 *    生产按需收紧，别用 * 全放。
 * ② 登录校验：/query、/approve、/mcp 必须先登录（校验 order-system 签发的 JWT），见 AgentAuthInterceptor。
 *    /mcp 也要登录：query_order 读的是订单数据，不能匿名查别人的订单；MCP 客户端在配置里
 *    带 Authorization: Bearer <token> 即可（Claude Desktop / Cursor 的 headers 字段）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;
    private final AgentAuthInterceptor authInterceptor;

    public WebConfig(@Value("${agent.cors.allowed-origins}") List<String> allowedOrigins,
                     AgentAuthInterceptor authInterceptor) {
        this.allowedOrigins = allowedOrigins;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/mcp")
                .allowedOriginPatterns(allowedOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/query", "/approve", "/mcp");
    }
}
