package com.orderagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 真正的权限闸门：先划边界，再给自由。
 * 只读工具（readOnly=true）直接放行；写工具默认拦截。
 *
 * 批准模型（相比早期"会话级批准"的重大收紧）：
 *   一次人工批准只放行「该会话最近被拦下的那一次写调用」——绑定 (userId, sessionId, toolName, 参数指纹)，
 *   存 WriteApprovalStore（Redis）带 TTL，成功执行后自动消费。
 *   模型中途换订单号、换工具、换用户、换会话、或批准过期，一律重新批准。
 *
 * 流转：blocks() 拦下写调用并记住它（pending）→ /approve 批准该次提议 → 下次同参数调用
 *       原子抢占批准（claim：读+比对+删一步完成）→ 放行执行
 *       → 业务失败 afterToolExecuted() 把批准放回（允许人工重试），成功则保持已消费（一次性）。
 *       并发保证：同一批准凭证被多个请求同时 claim，只有一个能抢到，其余被拦——批准绝不会被用两次。
 */
@Component
public class WritePermissionGate implements PermissionGate {

    private final Set<String> writeTools;
    private final WriteApprovalStore store;
    private final PendingStore pendingStore;
    private final ObjectMapper json = new ObjectMapper();

    public WritePermissionGate(PendingStore pendingStore, WriteApprovalStore store, List<Tool> tools) {
        this.pendingStore = pendingStore;
        this.store = store;
        this.writeTools = tools.stream()
                .filter(t -> !t.readOnly())
                .map(Tool::name)
                .collect(Collectors.toSet());
    }

    @Override
    public boolean blocks(ToolCall call, String sessionId, Long userId) {
        if (!writeTools.contains(call.name())) {
            return false; // 只读 → 放行
        }
        // 原子抢占批准：claim = "读+比对+删" 一步完成（Redis Lua / ConcurrentHashMap.remove(key, value)）。
        // 同一批准凭证被两个请求同时 claim，只有一个能成功——这就是"批准一次性、防双写"的原子保证。
        // 抢到 → 放行；没抢到（没批准 / 参数不符 / 已被别的请求抢走）→ 拦下并记 pending 等 /approve。
        if (store.claim(userId, sessionId, call.name(), fingerprint(call.args()))) {
            return false;
        }
        pendingStore.save(userId, sessionId, call.name(), fingerprint(call.args()));
        return true;
    }

    @Override
    public String reason(ToolCall call) {
        Object orderNo = call.args().get("orderNo");
        return (orderNo != null && !String.valueOf(orderNo).isBlank())
                ? "写操作被拦截：需要人工确认后才能执行（订单 " + orderNo + "）。"
                : "写操作被拦截：该操作会修改数据，需要人工确认后才能执行。";
    }

    /** 批准已在 blocks() 时被原子 claim 消费（一次性）。
     *  这里只处理"业务失败回存"：success=false 说明写操作没生效，把批准放回，
     *  允许用户原样重试；其余情况保持已消费（fail-closed）。真异常走 AgentLoop 的 catch，
     *  不经过这里，批准同样保持已消费——重试需重新批准。 */
    @Override
    public void afterToolExecuted(ToolCall call, String sessionId, Long userId, String result) {
        if (!writeTools.contains(call.name())) {
            return;
        }
        if (isBusinessFailure(result)) {
            String fp = fingerprint(call.args());
            if (fp != null) {
                store.approve(userId, sessionId, call.name(), fp);
            }
        }
    }

    /** 人工批准：放行该会话最近被拦下的那一次写调用（工具名+参数指纹）。 */
    public void approve(Long userId, String sessionId) {
        Pending pending = pendingStore.take(userId, sessionId);
        if (pending != null) {
            store.approve(userId, sessionId, pending.toolName(), pending.fingerprint());
        }
    }

    /** 参数指纹：Map → 规范化 JSON（TreeMap 按 key 排序，保证同样的参数不管 key 顺序如何都产出同一字符串）。
     *  Redis Lua 的 claim 用逐字节比对指纹，两边必须完全一致；
     *  序列化失败 → null → 无法匹配任何批准 → fail-closed 拦下 */
    private String fingerprint(Map<String, Object> args) {
        try {
            return json.writeValueAsString(json.valueToTree(new TreeMap<>(args)));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private boolean isBusinessFailure(String result) {
        return result != null && result.contains("\"success\":false");
    }
}
