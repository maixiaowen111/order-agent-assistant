package com.orderagent;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 版"最近被拦下的写提议"。key = agent:pending:{userId}:{sessionId}，value = toolName|fingerprint。
 * 放 Redis 的原因：多实例下 /approve 可能落在另一台实例，它要能读到这台实例拦下的提议。
 * 带短 TTL：用户长时间不点批准，被拦的提议自动作废，重新请求时重新记录。
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
        redis.opsForValue().set(key(userId, sessionId), toolName + "|" + fingerprint, TTL);
    }

    @Override
    public Pending take(Long userId, String sessionId) {
        String raw = redis.opsForValue().get(key(userId, sessionId));
        redis.delete(key(userId, sessionId));
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
