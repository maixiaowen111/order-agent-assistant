package com.orderagent;

/**
 * 一次 Agent 执行的上下文：把"这次请求是谁发的、问的什么、从什么时候开始、走到第几步"
 * 装在一个对象里。日志统一从它取数，不用在每个 log 点都传 sessionId/step 一堆参数。
 * step 在这里累加，顺便兼做 max-steps 的计数来源。
 */
public class AgentExecutionContext {

    private final String sessionId;
    private final String userQuery;
    private final long startTime;
    private int step;

    public AgentExecutionContext(String sessionId, String userQuery) {
        this.sessionId = sessionId;
        this.userQuery = userQuery;
        this.startTime = System.currentTimeMillis();
    }

    /** 下一步：step +1 并返回新值（模型调用、工具执行各用一次） */
    public int nextStep() {
        return ++step;
    }

    public String sessionId() {
        return sessionId;
    }

    public String userQuery() {
        return userQuery;
    }

    /** 距离请求开始过了多少毫秒 */
    public long elapsedMs() {
        return System.currentTimeMillis() - startTime;
    }
}
