package com.orderagent;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 只给 MCP 端点开 CORS。原因：官方调试工具 MCP Inspector 是浏览器应用，跨域会拦；
 * Electron 客户端（Claude Desktop / Cursor）本身不强制 CORS，开了也不影响。
 * 锁死在 /mcp 一个路径，其余接口不放开。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/mcp")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
