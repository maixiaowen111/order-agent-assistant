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
}
