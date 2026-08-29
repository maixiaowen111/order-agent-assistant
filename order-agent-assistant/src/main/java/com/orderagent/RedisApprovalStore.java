package com.orderagent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

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

    /**
     * 原子"读+比对+删"：GET 到的指纹必须等于期望指纹才 DEL。
     * Redis 单线程执行整个脚本，两个并发 claim 只有一个能看到值并删掉它，另一个读到 nil 返回 0。
     * 这就是"批准一次性、同一凭证只能被一个请求抢到"的原子保证。
     * （GET → 执行 → DELETE 两步之间有竞态窗口，不能用来抢占批准。）
     */
    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]);"
                    + "if v == ARGV[1] then return redis.call('DEL', KEYS[1]); else return 0; end",
            Long.class);

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

    @Override
    public boolean claim(Long userId, String sessionId, String toolName, String expectedFingerprint) {
        if (expectedFingerprint == null) {
            return false; // 指纹都算不出来 → 无法匹配任何批准 → fail-closed，不碰 Redis
        }
        Long result = redis.execute(CLAIM_SCRIPT, List.of(key(userId, sessionId, toolName)), expectedFingerprint);
        return result != null && result == 1L; // 1 = 指纹匹配且已删除（抢到）；0 = 没抢到
    }
}
