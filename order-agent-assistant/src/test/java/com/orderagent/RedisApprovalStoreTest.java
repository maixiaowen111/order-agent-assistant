package com.orderagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 一次性批准凭证的存储层测试：mock Redis，只测 key 形状 / TTL / 读写删。
 * 逻辑（一次性消费/绑定用户/参数指纹）在 WritePermissionGateTest / ToolProposalGateTest，
 * 这里只验证"Redis 落地"这一步没写错 key、没忘了 TTL。
 */
class RedisApprovalStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RedisApprovalStore store;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        store = new RedisApprovalStore(redis, 5);
    }

    @Test
    void approve写入带TTL的批准凭证() {
        store.approve(1L, "s1", "cancel_order", "{\"orderNo\":\"A\"}");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(key.capture(), value.capture(), ttl.capture());

        // key 带全三个维度：只有同一用户同一会话同一工具才能读到它
        assertThat(key.getValue()).isEqualTo("agent:approval:1:s1:cancel_order");
        assertThat(value.getValue()).isEqualTo("{\"orderNo\":\"A\"}");   // 存的是参数指纹
        assertThat(ttl.getValue()).isEqualTo(Duration.ofMinutes(5));     // 批准过期自动作废
    }

    @Test
    void fingerprint读回批准凭证() {
        when(ops.get("agent:approval:1:s1:cancel_order")).thenReturn("{\"orderNo\":\"A\"}");

        assertThat(store.fingerprint(1L, "s1", "cancel_order")).isEqualTo("{\"orderNo\":\"A\"}");
    }

    @Test
    void consume删除凭证_实现一次性() {
        store.consume(1L, "s1", "cancel_order");

        verify(redis).delete("agent:approval:1:s1:cancel_order");
    }
}
