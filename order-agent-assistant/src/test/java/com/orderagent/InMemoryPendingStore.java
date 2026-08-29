package com.orderagent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 测试用内存"最近被拦下的提议"：与 {@link RedisPendingStore} 同接口、同语义，不依赖 Redis。 */
public class InMemoryPendingStore implements PendingStore {

    private final Map<String, Pending> data = new ConcurrentHashMap<>();

    private String key(Long userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    @Override
    public void save(Long userId, String sessionId, String toolName, String fingerprint) {
        data.put(key(userId, sessionId), new Pending(toolName, fingerprint));
    }

    @Override
    public Pending take(Long userId, String sessionId) {
        return data.remove(key(userId, sessionId));
    }
}
