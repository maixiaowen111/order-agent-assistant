package com.orderagent;

/**
 * 一次性写操作批准凭证的存储抽象。
 * 生产实现 = Redis（跨实例共享，见 {@link RedisApprovalStore}）；测试用内存实现。
 *
 * 语义：一次批准只对「(userId, sessionId, toolName) + 参数指纹」生效，
 * 成功执行后必须 {@link #consume} 消费掉——防止"批准一次，该会话所有写操作都被放行"。
 */
public interface WriteApprovalStore {

    /** 记录一条批准：该用户在某个会话里批准了某个写工具 + 特定参数（带 TTL，过期作废）。 */
    void approve(Long userId, String sessionId, String toolName, String fingerprint);

    /** 取该批准记录的参数指纹；没有批准（或已过期）返回 null。 */
    String fingerprint(Long userId, String sessionId, String toolName);

    /** 成功执行后消费掉这条批准（一次性）。 */
    void consume(Long userId, String sessionId, String toolName);

    /**
     * 原子抢占这份批准：仅当存的参数指纹与 expectedFingerprint 完全一致时，读取并删除它。
     * 返回 true = 抢到了（已消费）；false = 没抢到（没有批准 / 参数不符 / 已被别的请求抢走）。
     * 必须原子：同一批准凭证被两个请求同时 claim，只允许一个成功——否则"批准一次"会被放行两次，
     * 写操作可能被执行两遍。实现用 Redis Lua 或 ConcurrentHashMap.remove(key, value)。
     */
    boolean claim(Long userId, String sessionId, String toolName, String expectedFingerprint);
}
