package com.orderagent;

/**
 * "最近一次被拦下的写提议"的存储抽象。
 * 生产实现 = Redis（见 {@link RedisPendingStore}）：/query 在 A 实例拦下提议、
 * /approve 在 B 实例也能读到并批准它——多实例下 /approve 必须能看到被拦的到底是什么。
 * 语义：一个会话同一时刻只记最近一条；take 取走即清（一次批准对应一次提议）。
 */
public interface PendingStore {

    /** 记下该会话最近一次被拦下的写提议。 */
    void save(Long userId, String sessionId, String toolName, String fingerprint);

    /** 取走并清除该会话最近被拦下的提议；没有返回 null。 */
    Pending take(Long userId, String sessionId);
}
