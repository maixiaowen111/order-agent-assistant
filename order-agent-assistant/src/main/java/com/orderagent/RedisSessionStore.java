package com.orderagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Redis 版会话记忆：把每个会话的消息历史以 JSON 存进 Redis。
 * key: agent:session:{sessionId}   value: List<PersistedMessage> 的 JSON
 *
 * 相比之前的内存 ConcurrentHashMap：
 *   ✅ 重启不丢、多实例共享（网关随便路由到哪台都有记忆）
 *   ✅ 带 TTL（30 分钟），没聊的会话自动过期清理，不会无限堆积
 *   ✅ 单会话消息条数上限，防历史无限增长撑爆 token
 *
 * 注意：getOrCreate 读到损坏数据时按新会话处理，不把整个 agent 打崩。
 */
@Component
public class RedisSessionStore implements SessionStore {

    private static final String PREFIX = "agent:session:";
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

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();

    public RedisSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<Message> getOrCreate(String sessionId) {
        String raw = redis.opsForValue().get(PREFIX + sessionId);
        List<PersistedMessage> list;
        if (raw == null || raw.isBlank()) {
            list = new ArrayList<>();
            list.add(PersistedMessage.from(Message.system(SYSTEM_PROMPT)));
        } else {
            try {
                list = json.readValue(raw, new TypeReference<List<PersistedMessage>>() {});
            } catch (Exception e) {
                // 数据坏了 → 当作新会话，别把整个对话打崩
                list = new ArrayList<>();
                list.add(PersistedMessage.from(Message.system(SYSTEM_PROMPT)));
            }
        }
        return list.stream()
                .map(PersistedMessage::toMessage)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public void save(String sessionId, List<Message> messages) {
        List<PersistedMessage> list = trim(messages).stream()
                .map(PersistedMessage::from)
                .collect(Collectors.toList());
        try {
            redis.opsForValue().set(PREFIX + sessionId, json.writeValueAsString(list), TTL);
        } catch (Exception e) {
            throw new RuntimeException("会话写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void bindOwner(String sessionId, Long userId) {
        redis.opsForValue().set(OWNER_PREFIX + sessionId, String.valueOf(userId), TTL);
    }

    @Override
    public Long ownerOf(String sessionId) {
        String raw = redis.opsForValue().get(OWNER_PREFIX + sessionId);
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
