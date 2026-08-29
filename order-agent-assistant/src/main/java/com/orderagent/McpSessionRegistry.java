package com.orderagent;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * MCP 客户端会话注册表：每个 MCP 客户端一次连接 = 一个独立 session（Mcp-Session-Id），
 * 绑定到登录用户（userId），存 Redis 带 TTL，每次请求续期。
 *
 * 为什么从固定 "mcp-&lt;userId&gt;" 改成真正的会话：
 *   · 固定命名空间下，同一用户的两个 MCP 客户端（比如两台设备/两个应用）共享一个
 *     pending/批准槽：一个客户端拦下的写操作，另一个客户端也能批准或把它挤掉——
 *     会话之间没有隔离。
 *   · 真正的 Mcp-Session-Id（规范的服务器可选能力，Claude Desktop / Cursor 都支持）：
 *     客户端 initialize 时服务器签发独立 id 并通过响应头带回，后续请求随头带回。
 *     这样每个客户端一个会话、一个 pending 槽、一套批准，互不干扰；同时又绑定 userId，
 *     /approve 校验归属，别人拿你的会话 id 批不了。
 *
 * Redis key：agent:mcp:{sessionId} → userId。30 分钟不活跃自动过期（客户端需重新 initialize）。
 */
@Component
public class McpSessionRegistry {

    private static final String PREFIX = "agent:mcp:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    public McpSessionRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 签发一个绑定到 userId 的新 MCP 会话（initialize 时调用），返回会话 id。 */
    public String create(Long userId) {
        String sid = "mcp-" + UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(PREFIX + sid, String.valueOf(userId), TTL);
        return sid;
    }

    /** 续期并返回绑定用户；会话不存在/已过期返回 null。 */
    public Long touch(String sessionId) {
        String key = PREFIX + sessionId;
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        redis.expire(key, TTL);
        return Long.valueOf(raw);
    }

    /** 查会话绑定用户；不存在返回 null。 */
    public Long ownerOf(String sessionId) {
        String raw = redis.opsForValue().get(PREFIX + sessionId);
        return raw == null ? null : Long.valueOf(raw);
    }
}
