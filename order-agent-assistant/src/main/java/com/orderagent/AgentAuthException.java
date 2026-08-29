package com.orderagent;

/**
 * 身份/归属相关错误：携带 HTTP 状态码，由 {@link AgentExceptionHandler} 转成响应。
 * 403 = 会话归属不符（越权访问他人会话）；401 = 未登录。
 */
public class AgentAuthException extends RuntimeException {

    private final int status;

    public AgentAuthException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
