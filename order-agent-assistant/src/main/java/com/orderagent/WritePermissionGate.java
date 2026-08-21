package com.orderagent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 真正的权限闸门：先划边界，再给自由。
 * 只读工具（readOnly=true）直接放行；写操作工具默认拦截，
 * 只有当某个会话被人工 approve 过才放行。
 */
@Component
public class WritePermissionGate implements PermissionGate {

    // 写操作工具名（来自 Tool.readOnly() == false 的工具）
    private final Set<String> writeTools;
    // 已批准的会话
    private final Set<String> approvedSessions = ConcurrentHashMap.newKeySet();

    public WritePermissionGate(List<Tool> tools) {
        this.writeTools = tools.stream()
                .filter(t -> !t.readOnly())
                .map(Tool::name)
                .collect(Collectors.toSet());
    }

    @Override
    public boolean blocks(ToolCall call, String sessionId) {
        if (!writeTools.contains(call.name())) {
            return false; // 只读 → 放行
        }
        return !approvedSessions.contains(sessionId); // 写 → 会话没批准就拦
    }

    @Override
    public String reason(ToolCall call) {
        Object orderNo = call.args().get("orderNo");
        return (orderNo != null && !String.valueOf(orderNo).isBlank())
                ? "写操作被拦截：需要人工确认后才能执行（订单 " + orderNo + "）。"
                : "写操作被拦截：该操作会修改数据，需要人工确认后才能执行。";
    }

    /** 人工批准：允许这个会话执行写操作。 */
    public void approve(String sessionId) {
        approvedSessions.add(sessionId);
    }
}
