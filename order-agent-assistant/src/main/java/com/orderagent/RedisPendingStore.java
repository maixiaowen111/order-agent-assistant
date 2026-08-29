package com.orderagent;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 版"最近被拦下的写提议"。key = agent:pending:{userId}:{sessionId}，value = toolName|fingerprint。
 * 放 Redis 的原因：多实例下 /approve 可能落在另一台实例，它要能读到这台实例拦下的提议。
 * 带短 TTL：用户长时间不点批准，被拦的提议自动作废，重新请求时重新记录。
 *
 * save 用 SETNX（先拦下的优先）：已有未批准的提议时不覆盖。
 * ——MCP 会话固定是 mcp-{userId}，所有写操作共用一个 pending 槽，若允许覆盖，
 *   后一个提议会把前一个顶掉，人点批准实际放行的是顶上来那个，之前拦下的静默丢失。
 */
@Component
public class RedisPendingStore implements PendingStore {

    private static final String PREFIX = "agent:pending:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public RedisPendingStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(Long userId, String sessionId) {
        return PREFIX + userId + ":" + sessionId;
    }

    @Override
    public void save(Long userId, String sessionId, String toolName, String fingerprint) {
        // SETNX：该会话已有未批准的提议 → 保留旧的（人看到的、要批准的始终是第一个被拦的）。
        redis.opsForValue().setIfAbsent(key(userId, sessionId), toolName + "|" + fingerprint, TTL);
    }

    @Override
    public Pending take(Long userId, String sessionId) {
        // GETDEL 原子"读取并删除"：两个 /approve 并发抢同一提议时，只有第一个能拿到值，
        // 第二个拿到 null——同一提议不会被批准两次。
        // 若用 GET → DELETE 两步，两个请求可能都读到同一条提议、批准两次（竞态窗口）。
        String raw = redis.opsForValue().getAndDelete(key(userId, sessionId));
        if (raw == null) {
            return null;
        }
        int sep = raw.indexOf('|');
        if (sep <= 0) {
            return null;
        }
        return new Pending(raw.substring(0, sep), raw.substring(sep + 1));
    }
}
