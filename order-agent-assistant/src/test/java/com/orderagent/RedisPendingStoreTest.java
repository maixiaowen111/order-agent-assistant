package com.orderagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisPendingStore 测试：mock 掉 Redis，专测 key 拼接和命令选择。
 * 重点：save 用 SETNX（setIfAbsent）而非覆盖式 set——MCP 固定 mcp-{userId} 槽位上
 * 先拦下的提议优先，后一个不顶掉前一个；take 用原子 GETDEL。
 * （"不覆盖"的完整语义见 InMemoryPendingStoreTest——内存实现跑真逻辑验证。）
 */
class RedisPendingStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RedisPendingStore store;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        store = new RedisPendingStore(redis);
    }

    @Test
    void save用SETNX_已有未批准提议时不覆盖() {
        store.save(1L, "mcp-1", "cancel_order", "{\"orderNo\":\"A\"}");

        // key = agent:pending:{userId}:{sessionId}，10 分钟 TTL，setIfAbsent = 只写不覆盖
        verify(ops).setIfAbsent(eq("agent:pending:1:mcp-1"),
                eq("cancel_order|{\"orderNo\":\"A\"}"), eq(Duration.ofMinutes(10)));
    }

    @Test
    void take用getAndDelete_解析出工具名和指纹() {
        when(ops.getAndDelete("agent:pending:1:mcp-1"))
                .thenReturn("cancel_order|{\"orderNo\":\"A\"}");

        Pending taken = store.take(1L, "mcp-1");

        assertThat(taken.toolName()).isEqualTo("cancel_order");
        assertThat(taken.fingerprint()).isEqualTo("{\"orderNo\":\"A\"}");
    }

    @Test
    void 无pending时take返回null() {
        when(ops.getAndDelete(any(String.class))).thenReturn(null);

        assertThat(store.take(1L, "mcp-1")).isNull();
    }

    @Test
    void 损坏的value_take返回null不抛() {
        when(ops.getAndDelete(any(String.class))).thenReturn("no-separator");

        assertThat(store.take(1L, "mcp-1")).isNull();
    }
}
