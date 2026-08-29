package com.orderagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话存储测试：mock 掉 Redis，专测 store 自己的逻辑——
 * 新建/读回（带版本号）/损坏数据兜底/Lua CAS 保存/SETNX 归属/超限裁剪。
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

    /** 让 Lua 保存脚本"成功"（Redis 返回 1）；脚本内容私有，用 any 匹配。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubSaveOk() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
    }

    @Test
    void 会话不存在时新建_自带系统提示词_版本0() {
        when(ops.get("agent:session:s1")).thenReturn(null);
        when(ops.get("agent:ver:s1")).thenReturn(null);

        SessionSnapshot snapshot = store.getOrCreate("s1");

        assertThat(snapshot.messages()).hasSize(1);
        assertThat(snapshot.messages().get(0).role()).isEqualTo("system");
        assertThat(snapshot.version()).isZero(); // 新会话版本从 0 开始，CAS 从 0 写起
    }

    @Test
    void 已有会话时完整读回() throws Exception {
        String saved = json.writeValueAsString(List.of(
                new PersistedMessage("system", "你是助手", List.of(), null),
                new PersistedMessage("user", "你好", List.of(), null)
        ));
        when(ops.get("agent:session:s1")).thenReturn(saved);
        when(ops.get("agent:ver:s1")).thenReturn("2");

        SessionSnapshot snapshot = store.getOrCreate("s1");

        assertThat(snapshot.messages()).hasSize(2);
        assertThat(snapshot.messages().get(1).role()).isEqualTo("user");
        assertThat(snapshot.messages().get(1).content()).isEqualTo("你好");
        assertThat(snapshot.version()).isEqualTo(2); // 版本跟历史一起读回，保存时当乐观锁
    }

    @Test
    void 数据损坏时按新会话处理_不把agent打崩() {
        when(ops.get("agent:session:s1")).thenReturn("{{not-json");
        when(ops.get("agent:ver:s1")).thenReturn(null);

        SessionSnapshot snapshot = store.getOrCreate("s1");

        assertThat(snapshot.messages()).hasSize(1);
        assertThat(snapshot.messages().get(0).role()).isEqualTo("system");
    }

    @Test
    void 版本key数据损坏按0处理() {
        when(ops.get("agent:session:s1")).thenReturn(null);
        when(ops.get("agent:ver:s1")).thenReturn("abc");

        SessionSnapshot snapshot = store.getOrCreate("s1");

        assertThat(snapshot.version()).isZero();
    }

    @Test
    void saveIfUnchanged_期望版本匹配_执行Lua并返回true() {
        stubSaveOk();

        boolean ok = store.saveIfUnchanged("s1", List.of(Message.user("你好")), 0);

        assertThat(ok).isTrue();
        // 脚本一次拿到三个 key：消息 + 版本 + 归属；参数 = 期望版本 / 消息 JSON / TTL 秒
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("agent:session:s1", "agent:ver:s1", "agent:owner:s1")),
                any(Object[].class));
    }

    @Test
    void saveIfUnchanged_归属key一并传给脚本_保存成功顺带续期owner() {
        // owner 只在绑定那一刻给一次 TTL，若保存时不续期，活跃会话聊超 30 分钟 owner 先过期、
        // 消息还活着 → 别人能抢绑读历史。所以脚本的 KEYS[3] 必须带上 owner key。
        stubSaveOk();

        store.saveIfUnchanged("s1", List.of(Message.user("你好")), 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly("agent:session:s1", "agent:ver:s1", "agent:owner:s1");
    }

    @Test
    void saveIfUnchanged_版本冲突_返回false不写() {
        // Redis 返回 0 = 当前版本已不是期望版本（被并发请求改掉了）→ 不能写
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        boolean ok = store.saveIfUnchanged("s1", List.of(Message.user("你好")), 0);

        assertThat(ok).isFalse();
    }

    @Test
    void saveIfUnchanged_写出合法JSON并带30分钟TTL() throws Exception {
        stubSaveOk();
        store.saveIfUnchanged("s1", List.of(Message.user("你好")), 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("agent:session:s1", "agent:ver:s1", "agent:owner:s1")), args.capture());

        assertThat(args.getValue()[0]).isEqualTo("0");          // 期望版本
        assertThat(args.getValue()[2]).isEqualTo("1800");       // 30 分钟 TTL（秒）

        List<PersistedMessage> parsed = json.readValue((String) args.getValue()[1],
                new TypeReference<List<PersistedMessage>>() {});
        assertThat(parsed).anyMatch(p -> "user".equals(p.role()) && "你好".equals(p.text()));
    }

    @Test
    void bindOwnerIfAbsent_无归属_绑定并返回true() {
        when(ops.setIfAbsent("agent:owner:s1", "1", Duration.ofMinutes(30))).thenReturn(true);

        boolean ok = store.bindOwnerIfAbsent("s1", 1L);

        assertThat(ok).isTrue();
        // 归属和会话分开存，防混；SETNX = 只有无归属时才写成功
        verify(ops).setIfAbsent("agent:owner:s1", "1", Duration.ofMinutes(30));
    }

    @Test
    void bindOwnerIfAbsent_已有归属_返回false() {
        // SETNX 返回 false = 同一瞬间别人先绑定了这个会话 → 抢不过
        when(ops.setIfAbsent("agent:owner:s1", "1", Duration.ofMinutes(30))).thenReturn(false);

        assertThat(store.bindOwnerIfAbsent("s1", 1L)).isFalse();
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
        stubSaveOk();
        List<Message> many = new java.util.ArrayList<>();
        many.add(Message.system("提示"));
        for (int i = 0; i < 60; i++) {
            many.add(Message.user("消息" + i));
        }

        store.saveIfUnchanged("s1", many, 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), args.capture());
        List<PersistedMessage> parsed = json.readValue((String) args.getValue()[1],
                new TypeReference<List<PersistedMessage>>() {});
        assertThat(parsed).hasSize(50);
        assertThat(parsed.get(0).role()).isEqualTo("system");  // 系统提示词必须保留
        assertThat(parsed.get(49).text()).isEqualTo("消息59");   // 最近的在尾部
        assertThat(parsed).noneMatch(p -> p.text().equals("消息5")); // 中间的裁掉
    }
}
