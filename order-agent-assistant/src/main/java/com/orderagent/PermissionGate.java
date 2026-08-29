package com.orderagent;

/**
 * 权限闸门：插在"模型决定调工具"和"真正执行工具"之间。
 * 对应 s03「先划边界再给自由」——只读工具放行，写工具要人确认。
 *
 * 三个方法都要知道"是哪个用户"（userId）：
 *   批准凭证绑定 (userId, sessionId, toolName, 参数指纹)，只有同一用户在同一个会话里
 *   发起的同参数调用才能命中——换用户、换会话、换参数、换工具都拦。
 */
public interface PermissionGate {

    boolean blocks(ToolCall call, String sessionId, Long userId);

    default String reason(ToolCall call) {
        return "工具被拒绝：" + call.name();
    }

    /**
     * 工具执行完后的通知：result 里含 "success":false 表示业务失败。
     * 闸门可据此消费一次性批准（成功才消费，失败保留以便人工重试）。
     */
    default void afterToolExecuted(ToolCall call, String sessionId, Long userId, String result) {
    }
}
