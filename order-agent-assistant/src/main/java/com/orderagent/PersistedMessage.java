package com.orderagent;

import java.util.List;

/**
 * 会话持久化的中间格式。
 *
 * 为什么不能直接序列化 Message？
 *   Message.content 是 Object：assistant 消息里是 LlmResponse，其他消息里是 String。
 *   存的时候 Jackson 能按运行时类型写对，但读的时候它不知道该还原成哪种类型。
 *   所以先摊平成这个扁平的格式（text + toolCalls 分开放）再存，读出来再还原成 Message。
 */
public record PersistedMessage(String role, String text, List<ToolCall> toolCalls, String toolCallId) {

    static PersistedMessage from(Message m) {
        if ("assistant".equals(m.role()) && m.content() instanceof LlmResponse resp) {
            return new PersistedMessage(m.role(), resp.text(), resp.toolCalls(), m.toolCallId());
        }
        return new PersistedMessage(m.role(), String.valueOf(m.content()), List.of(), m.toolCallId());
    }

    Message toMessage() {
        if ("assistant".equals(role)) {
            return new Message(role, new LlmResponse(text, toolCalls == null ? List.of() : toolCalls), toolCallId);
        }
        return new Message(role, text, toolCallId);
    }
}
