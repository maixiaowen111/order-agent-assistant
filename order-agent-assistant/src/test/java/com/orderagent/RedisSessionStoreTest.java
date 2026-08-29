package com.orderagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话存储测试：mock 掉 Redis，专测 store 自己的逻辑——
 * 新建/读回/损坏数据兜底/写出JSON与TTL/超限裁剪。
 * 不需要真 Redis 就能跑，任何机器上都能绿。
 */
class RedisSessionStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RedisSessionStore store;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        store = new RedisSessionStore(redis);
    }

    @Test
    void 会话不存在时新建_自带系统提示词() {
        when(ops.get("agent:session:s1")).thenReturn(null);

        List<Message> messages = store.getOrCreate("s1");

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo("system");
    }

    @Test
    void 已有会话时完整读回() throws Exception {
        String saved = json.writeValueAsString(List.of(
                new PersistedMessage("system", "你是助手", List.of(), null),
                new PersistedMessage("user", "你好", List.of(), null)
        ));
        when(ops.get("agent:session:s1")).thenReturn(saved);

        List<Message> messages = store.getOrCreate("s1");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("你好");
    }

    @Test
    void 数据损坏时按新会话处理_不把agent打崩() {
        when(ops.get("agent:session:s1")).thenReturn("{{not-json");

        List<Message> messages = store.getOrCreate("s1");

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo("system");
    }

    @Test
    void save写出合法JSON并带30分钟TTL() throws Exception {
        store.save("s1", List.of(Message.user("你好"), Message.system("提示")));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(key.capture(), value.capture(), ttl.capture());

        assertThat(key.getValue()).isEqualTo("agent:session:s1");
        assertThat(ttl.getValue()).isEqualTo(Duration.ofMinutes(30));

        List<PersistedMessage> parsed = json.readValue(value.getValue(),
                new TypeReference<List<PersistedMessage>>() {});
        assertThat(parsed).anyMatch(p -> "user".equals(p.role()) && "你好".equals(p.text()));
    }

    @Test
    void bindOwner写归属并带30分钟TTL() {
        store.bindOwner("s1", 1L);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(key.capture(), value.capture(), ttl.capture());

        assertThat(key.getValue()).isEqualTo("agent:owner:s1");   // 归属和会话分开存，防混
        assertThat(value.getValue()).isEqualTo("1");
        assertThat(ttl.getValue()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void ownerOf读回归属用户() {
        when(ops.get("agent:owner:s1")).thenReturn("1");
        assertThat(store.ownerOf("s1")).isEqualTo(1L);
    }

    @Test
    void ownerOf无归属返回null() {
        when(ops.get("agent:owner:s1")).thenReturn(null);
        assertThat(store.ownerOf("s1")).isNull();
    }

    @Test
    void 超限裁剪_保留系统提示词和最近的消息() throws Exception {
        List<Message> many = new java.util.ArrayList<>();
        many.add(Message.system("提示"));
        for (int i = 0; i < 60; i++) {
            many.add(Message.user("消息" + i));
        }

        store.save("s1", many);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(ops).set(any(), value.capture(), any());
        List<PersistedMessage> parsed = json.readValue(value.getValue(),
                new TypeReference<List<PersistedMessage>>() {});
        assertThat(parsed).hasSize(50);
        assertThat(parsed.get(0).role()).isEqualTo("system");  // 系统提示词必须保留
        assertThat(parsed.get(49).text()).isEqualTo("消息59");   // 最近的在尾部
        assertThat(parsed).noneMatch(p -> p.text().equals("消息5")); // 中间的裁掉
    }
}
