package com.orderagent;

/**
 * 一条消息。
 * 关键：role 是 "tool" 的消息必须带 toolCallId —— 用来"还号牌"，
 * 把工具结果绑回模型那一次具体的调用上（否则模型分不清结果对应哪次调用）。
 */
public record Message(String role, Object content, String toolCallId) {

    static Message user(Object content) {
        return new Message("user", content, null);
    }

    static Message assistant(Object content) {
        return new Message("assistant", content, null);
    }

    static Message tool(String toolCallId, Object content) {
        return new Message("tool", content, toolCallId);
    }

    static Message system(Object content) {
        return new Message("system", content, null);
    }
}
