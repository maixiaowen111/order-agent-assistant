package com.orderagent;

/**
 * POST /approve 的请求体。只带 sessionId（批准"该会话最近被拦下的那一次写调用"）。
 * 同样用 DTO 代替裸 Map，sessionId 有长度/格式校验。
 */
public record AgentApproveRequest(String sessionId) {
}
