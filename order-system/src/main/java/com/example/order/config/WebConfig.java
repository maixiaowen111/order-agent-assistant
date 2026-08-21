package com.example.order.config;

import com.example.order.interceptor.LoginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置 — 注册拦截器
 *
 * 核心决策：哪些接口需要登录，哪些不需要？
 *
 * 放行（不需要 Token）：
 *   /api/user/register  注册
 *   /api/user/login     登录
 *   /api/product/**     商品查询（浏览商品不需要登录）
 *
 * 拦截（需要 Token）：
 *   /api/cart/**        购物车（需要知道是谁的购物车）
 *   /api/order/**       订单（需要知道是谁的订单）
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    /** 商品图片存储目录（本地默认 ./uploads，Docker 用 APP_UPLOAD_DIR=/app/uploads 覆盖） */
    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 静态资源映射：/uploads/** → 磁盘 uploads 目录。
     * 本地 8080 直接可访问商品图片；Docker 里由 nginx location /uploads/ 反代到本服务。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String dir = uploadDir.endsWith("/") ? uploadDir : uploadDir + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + dir);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")              // 拦截所有
                .excludePathPatterns(                     // 放行以下（不需要登录）
                        "/api/user/register",
                        "/api/user/login",
                        "/api/product/page",             // 商品列表（公开）
                        "/internal/**"                   // 内部接口：靠 X-Internal-Key 密钥，不走用户登录
                        // 注意：商品详情 GET /api/product/{id} 的公开豁免不再放这里——
                        //       URL 通配符 "*" 不区分 HTTP 方法，会连管理员的 PUT（编辑商品）
                        //       一起放行（放行=不解析 Token，checkAdmin() 会误报 403）。
                        //       改为 LoginInterceptor.preHandle 里按「GET + 单级路径」精确豁免。
                        // 管理接口 /api/product/admin/** 与 POST|PUT /api/product 需要登录+管理员
                );
    }
}
