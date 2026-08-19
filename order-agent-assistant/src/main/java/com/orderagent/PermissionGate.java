package com.orderagent;

/**
 * 权限闸门：插在"模型决定调工具"和"真正执行工具"之间。
 * 对应 s03「先划边界再给自由」——只读工具放行，写工具要人确认。
 */
public interface PermissionGate {

    boolean blocks(ToolCall call, String sessionId);

    default String reason(ToolCall call) {
        return "工具被拒绝：" + call.name();
    }
}
