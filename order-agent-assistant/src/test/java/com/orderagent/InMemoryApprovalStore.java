package com.orderagent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存批准凭证：与 {@link RedisApprovalStore} 同接口、同语义，但不依赖 Redis。
 * 验证闸门逻辑（一次性/绑定用户/参数指纹/成功后消费）时用这个。
 * 测"多实例共享批准"就建两个闸门共用一个实例——Redis 存一处、处处读的等价物。
 */
public class InMemoryApprovalStore implements WriteApprovalStore {

    private final Map<String, String> data = new ConcurrentHashMap<>();

    private String key(Long userId, String sessionId, String toolName) {
        return userId + ":" + sessionId + ":" + toolName;
    }

    @Override
    public void approve(Long userId, String sessionId, String toolName, String fingerprint) {
        data.put(key(userId, sessionId, toolName), fingerprint);
    }

    @Override
    public String fingerprint(Long userId, String sessionId, String toolName) {
        return data.get(key(userId, sessionId, toolName));
    }

    @Override
    public void consume(Long userId, String sessionId, String toolName) {
        data.remove(key(userId, sessionId, toolName));
    }

    @Override
    public boolean claim(Long userId, String sessionId, String toolName, String expectedFingerprint) {
        if (expectedFingerprint == null) {
            return false;
        }
        // ConcurrentHashMap.remove(key, value) 原子：仅当 key 当前映射到该值才删除——
        // 与 Redis Lua 的"读+比对+删"语义等价，两个并发 claim 只有一个能成功。
        return data.remove(key(userId, sessionId, toolName), expectedFingerprint);
    }
}
