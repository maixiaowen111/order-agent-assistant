package com.orderagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Redis 版会话记忆：消息历史存 agent:session:{sessionId}（JSON），版本存 agent:ver:{sessionId}（整数串），
 * 归属存 agent:owner:{sessionId}。
 *
 * 并发安全（见 SessionStore 接口注释）：
 *   - 保存带版本号 CAS：用 Lua 一步"读版本 → 比对 → 相等才写新值并把版本 +1"。
 *     两个并发请求同时写同一会话，只有一个成功，另一个拿到 false——绝不互相覆盖历史（丢消息）。
 *   - 归属绑定用 SETNX（setIfAbsent）：两个请求同时抢同一个新会话，只有一个能赢。
 *
 * 相比之前的内存 ConcurrentHashMap：
 *   ✅ 重启不丢、多实例共享（网关随便路由到哪台都有记忆）
 *   ✅ 带 TTL（30 分钟），没聊的会话自动过期清理，不会无限堆积
 *   ✅ 单会话消息条数上限，防历史无限增长撑爆 token
 *   ✅ 版本号 CAS，两个请求同时读改写同一会话不丢消息（这是本次加的核心）
 *
 * 注意：getOrCreate 读到损坏数据时按新会话处理，不把整个 agent 打崩。
 */
@Component
public class RedisSessionStore implements SessionStore {

    private static final String PREFIX = "agent:session:";
    private static final String VERSION_PREFIX = "agent:ver:";
    private static final String OWNER_PREFIX = "agent:owner:";
    private static final Duration TTL = Duration.ofMinutes(30);
    /** 单个会话最多保留的消息条数：第 1 条是系统提示词（必须留），其余留最近的 */
    private static final int MAX_MESSAGES = 50;
    private static final String SYSTEM_PROMPT =
            "你是订单助手，负责帮用户查询和管理订单，始终用中文回答。\n" +
            "【核心规则：按用户意图选工具，禁止用错工具】\n" +
            "- 用户消息里出现“取消”（如“帮我取消订单 XX”）→ 立即调用 cancel_order 工具，禁止调用 query_order，禁止反问“是否要取消”，禁止先查单再决定。\n" +
            "- 用户只问状态/查询（如“帮我查一下订单”）→ 调用 query_order 工具。\n" +
            "- 调用 cancel_order 被拦截（工具返回“写操作被拦截”）→ 向用户转告：“取消订单需要人工确认后才能执行”，并附上订单号。\n" +
            "- 用户表示“已批准/已确认”后 → 再次调用 cancel_order 工具真正执行。\n" +
            "- 工具返回 JSON 里 success=false 表示执行失败 → 把 message 里的原因原样转告给用户，禁止编造成功结果。\n" +
            "【输出格式】最终回答必须是一个 JSON 对象，禁止 Markdown 代码块、禁止任何多余文字，字段固定为：answer（给用户看的自然语言回答）、orderNo（涉及的订单号，没有则空字符串）、status（订单状态，没有则空字符串）、amount（金额，没有则空字符串）。";

    /**
     * 乐观锁保存（CAS）：
     *   KEYS[1] = 消息 key，KEYS[2] = 版本 key，KEYS[3] = 归属(owner) key
     *   ARGV[1] = 期望版本，ARGV[2] = 消息 JSON，ARGV[3] = TTL 秒
     * 语义：当前版本 == 期望版本 才写入（新会话 = 版本 key 不存在且期望 0），并原子地版本 +1。
     * 两个并发保存同一会话，第二个会因为版本已被第一个改掉而返回 0，绝不覆盖。
     * Redis 单线程执行整个脚本，读+写之间没有竞态窗口。
     *
     * 归属也要在同一脚本里续期：消息 TTL 每次保存都刷新，若 owner TTL 只在绑定那一刻给一次，
     * 一个活跃会话聊超过 30 分钟，owner 会先过期、消息还活着——别人就能抢绑并读到历史。
     * 归属存在才续（没绑定的会话不写多余命令），这样 owner 与消息同生命周期，永不比消息先死。
     */
    private static final DefaultRedisScript<Long> SAVE_IF_UNCHANGED = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[2]);"
                    + "if cur == false then"
                    + "  if ARGV[1] == '0' then"
                    + "    redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]);"
                    + "    redis.call('SET', KEYS[2], '1', 'EX', ARGV[3]);"
                    + "    if redis.call('EXISTS', KEYS[3]) == 1 then redis.call('EXPIRE', KEYS[3], ARGV[3]); end"
                    + "    return 1;"
                    + "  end"
                    + "  return 0;"
                    + "end;"
                    + "if cur == ARGV[1] then"
                    + "  redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]);"
                    + "  redis.call('SET', KEYS[2], tostring(tonumber(cur) + 1), 'EX', ARGV[3]);"
                    + "  if redis.call('EXISTS', KEYS[3]) == 1 then redis.call('EXPIRE', KEYS[3], ARGV[3]); end"
                    + "  return 1;"
                    + "end;"
                    + "return 0;",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();

    public RedisSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String sessionId) {
        return PREFIX + sessionId;
    }

    private String versionKey(String sessionId) {
        return VERSION_PREFIX + sessionId;
    }

    private String ownerKey(String sessionId) {
        return OWNER_PREFIX + sessionId;
    }

    @Override
    public SessionSnapshot getOrCreate(String sessionId) {
        String raw = redis.opsForValue().get(key(sessionId));
        List<PersistedMessage> list;
        if (raw == null || raw.isBlank()) {
            list = new ArrayList<>();
            list.add(PersistedMessage.from(Message.system(SYSTEM_PROMPT)));
        } else {
            try {
                list = json.readValue(raw, new TypeReference<List<PersistedMessage>>() {
                });
            } catch (Exception e) {
                // 数据坏了 → 当作新会话，别把整个对话打崩
                list = new ArrayList<>();
                list.add(PersistedMessage.from(Message.system(SYSTEM_PROMPT)));
            }
        }
        int version = readVersion(sessionId);
        return new SessionSnapshot(
                list.stream().map(PersistedMessage::toMessage).collect(Collectors.toCollection(ArrayList::new)),
                version);
    }

    /** 读版本号；版本 key 不存在（从未保存/已过期）按 0 处理。 */
    private int readVersion(String sessionId) {
        String raw = redis.opsForValue().get(versionKey(sessionId));
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0; // 数据坏了 → 按 0，下一次保存会以"新会话"语义重建版本
        }
    }

    @Override
    public boolean saveIfUnchanged(String sessionId, List<Message> messages, int expectedVersion) {
        List<PersistedMessage> list = trim(messages).stream()
                .map(PersistedMessage::from)
                .collect(Collectors.toList());
        try {
            String body = json.writeValueAsString(list);
            Long result = redis.execute(SAVE_IF_UNCHANGED,
                    List.of(key(sessionId), versionKey(sessionId), ownerKey(sessionId)),
                    String.valueOf(expectedVersion), body, String.valueOf(TTL.getSeconds()));
            return result != null && result == 1L;
        } catch (Exception e) {
            throw new RuntimeException("会话写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean bindOwnerIfAbsent(String sessionId, Long userId) {
        // SETNX：仅当该会话还没有归属才绑定。两个并发请求抢同一个新会话，只有一个能成功。
        Boolean ok = redis.opsForValue().setIfAbsent(ownerKey(sessionId), String.valueOf(userId), TTL);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public Long ownerOf(String sessionId) {
        String raw = redis.opsForValue().get(ownerKey(sessionId));
        return raw == null ? null : Long.valueOf(raw);
    }

    private List<Message> trim(List<Message> messages) {
        if (messages.size() <= MAX_MESSAGES) {
            return messages;
        }
        List<Message> head = new ArrayList<>();
        head.add(messages.get(0)); // 系统提示词必须保留
        head.addAll(messages.subList(messages.size() - (MAX_MESSAGES - 1), messages.size()));
        return head;
    }
}
