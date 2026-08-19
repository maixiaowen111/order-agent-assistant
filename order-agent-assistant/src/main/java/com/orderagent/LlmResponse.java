package com.orderagent;

import java.util.List;

/**
 * 一次模型返回：要么给文字答案(text)，要么给一批工具调用(toolCalls)。
 */
public record LlmResponse(String text, List<ToolCall> toolCalls) {

    boolean wantsTools() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
