package com.orderagent;

import java.util.Map;

/**
 * 模型发来的一次工具调用。
 * id   —— 模型发的"号牌"，结果要原样还回去；
 * name —— 工具名；
 * args —— 参数（模型填的，不能全信，见 QueryOrderTool.run）。
 */
public record ToolCall(String id, String name, Map<String, Object> args) {}
