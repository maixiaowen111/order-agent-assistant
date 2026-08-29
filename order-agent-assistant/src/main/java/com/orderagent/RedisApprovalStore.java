package com.orderagent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 版一次性批准凭证。key = agent:approval:{userId}:{sessionId}:{toolName}，value = 参数指纹(JSON)。
 *
 * 为什么放 Redis：agent 将来多实例部署时，/approve 落在 A 实例、执行落在 B 实例，
 * 内存 ConcurrentHashMap 两边互相看不见；Redis 一处存、处处读，批准状态全局共享。
 * 带 TTL：批准长期不执行自动作废，防止"批准一次放行所有写操作"。
 */
@Component
public class RedisApprovalStore implements WriteApprovalStore {

    private static final String PREFIX = "agent:approval:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisApprovalStore(StringRedisTemplate redis,
                              @Value("${agent.approval-ttl-minutes:5}") int ttlMinutes) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    private String key(Long userId, String sessionId, String toolName) {
        return PREFIX + userId + ":" + sessionId + ":" + toolName;
    }

    @Override
    public void approve(Long userId, String sessionId, String toolName, String fingerprint) {
        redis.opsForValue().set(key(userId, sessionId, toolName), fingerprint, ttl);
    }

    @Override
    public String fingerprint(Long userId, String sessionId, String toolName) {
        return redis.opsForValue().get(key(userId, sessionId, toolName));
    }

    @Override
    public void consume(Long userId, String sessionId, String toolName) {
        redis.delete(key(userId, sessionId, toolName));
    }
}
