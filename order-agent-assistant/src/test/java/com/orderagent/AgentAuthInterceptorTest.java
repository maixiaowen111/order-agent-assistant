package com.orderagent;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * /query、/approve 的登录拦截器测试：没有合法 Bearer token → 401 不放行；
 * 有合法 token → 放行并把 userId 放进 AgentUserContext；请求结束清掉。
 * OPTIONS 预检（CORS）不需要身份。
 *
 * 黑名单校验：order-system 退出登录时写 TOKEN_VER:{userId}=退出时间戳。
 * token 签发时间早于退出时间 → 401（旧 token 已失效）；黑名单没记录 → 放行。
 */
class AgentAuthInterceptorTest {

    private static final String SECRET = "MyOrderSystemSecretKeyForJwtToken2024!!!";
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private AgentAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        interceptor = new AgentAuthInterceptor(new AgentJwtService(SECRET), redis);
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

    /** 指定签发时间的 token（测黑名单要用"过去的签发时间"）。 */
    private String tokenIssuedAt(long userId, Date issuedAt) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(issuedAt)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private MockHttpServletRequest authRequest(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query");
        request.addHeader("Authorization", authorization);
        return request;
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
        MockHttpServletRequest request = authRequest("Token abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void 伪造token_401() throws Exception {
        MockHttpServletRequest request = authRequest("Bearer not-a-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void 合法token_黑名单无记录_放行并把userId放进上下文() throws Exception {
        MockHttpServletRequest request = authRequest("Bearer " + token(7L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(AgentUserContext.get()).isEqualTo(7L);

        // 请求结束必须清 ThreadLocal，否则下个请求/线程会串身份
        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(AgentUserContext.get()).isNull();
    }

    @Test
    void 退出登录后的旧token_黑名单命中_401() throws Exception {
        // token 是 1 分钟前签发的，而退出时间戳是"现在" → iat < 退出时间 → 旧 token 已失效
        when(ops.get("TOKEN_VER:7")).thenReturn(String.valueOf(System.currentTimeMillis()));
        MockHttpServletRequest request = authRequest("Bearer " + tokenIssuedAt(7L, new Date(System.currentTimeMillis() - 60_000)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(AgentUserContext.get()).isNull();   // 身份没放进上下文
    }

    @Test
    void 退出后重新登录的新token_黑名单不命中_放行() throws Exception {
        // 退出时间戳是 1 分钟前，token 是"现在"签发的（重新登录拿的新 token）→ iat 晚于退出 → 有效
        when(ops.get("TOKEN_VER:7")).thenReturn(String.valueOf(System.currentTimeMillis() - 60_000));
        MockHttpServletRequest request = authRequest("Bearer " + token(7L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(AgentUserContext.get()).isEqualTo(7L);
    }

    @Test
    void 黑名单值为非数字_按未拉黑放行_不误伤正常登录() throws Exception {
        when(ops.get(anyString())).thenReturn("not-a-number");
        MockHttpServletRequest request = authRequest("Bearer " + token(7L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void OPTIONS预检_不需身份_放行() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }
}
