package com.orderagent;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /query、/approve 的登录拦截器测试：没有合法 Bearer token → 401 不放行；
 * 有合法 token → 放行并把 userId 放进 AgentUserContext；请求结束清掉。
 * OPTIONS 预检（CORS）不需要身份。
 */
class AgentAuthInterceptorTest {

    private static final String SECRET = "MyOrderSystemSecretKeyForJwtToken2024!!!";
    private AgentAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AgentAuthInterceptor(new AgentJwtService(SECRET));
    }

    @AfterEach
    void tearDown() {
        AgentUserContext.clear();
    }

    private String token(long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void 无Authorization头_401_不放行() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("未登录");
    }

    @Test
    void 非Bearer格式_401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query");
        request.addHeader("Authorization", "Token abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void 伪造token_401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query");
        request.addHeader("Authorization", "Bearer not-a-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void 合法token_放行并把userId放进上下文() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query");
        request.addHeader("Authorization", "Bearer " + token(7L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(AgentUserContext.get()).isEqualTo(7L);

        // 请求结束必须清 ThreadLocal，否则下个请求/线程会串身份
        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(AgentUserContext.get()).isNull();
    }

    @Test
    void OPTIONS预检_不需身份_放行() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }
}
