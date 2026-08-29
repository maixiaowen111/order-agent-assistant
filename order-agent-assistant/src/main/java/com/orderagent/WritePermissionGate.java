package com.orderagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * 流转：blocks() 拦下写调用并记住它（pending）→ /approve 批准该次提议 → 下次同参数调用放行
 *       → 执行成功后 afterToolExecuted() 消费批准（一次性）。
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
        if (approved(call, sessionId, userId)) {
            return false; // 已批准且参数一致 → 放行（执行成功后由 afterToolExecuted 消费）
        }
        pendingStore.save(userId, sessionId, call.name(), fingerprint(call.args())); // 记下这次被拦的提议，等 /approve
        return true;
    }

    @Override
    public String reason(ToolCall call) {
        Object orderNo = call.args().get("orderNo");
        return (orderNo != null && !String.valueOf(orderNo).isBlank())
                ? "写操作被拦截：需要人工确认后才能执行（订单 " + orderNo + "）。"
                : "写操作被拦截：该操作会修改数据，需要人工确认后才能执行。";
    }

    /** 成功执行后消费批准：一次性，防复用；业务失败保留，允许人工重试 */
    @Override
    public void afterToolExecuted(ToolCall call, String sessionId, Long userId, String result) {
        if (!writeTools.contains(call.name()) || isBusinessFailure(result)) {
            return;
        }
        store.consume(userId, sessionId, call.name());
    }

    /** 人工批准：放行该会话最近被拦下的那一次写调用（工具名+参数指纹）。 */
    public void approve(Long userId, String sessionId) {
        Pending pending = pendingStore.take(userId, sessionId);
        if (pending != null) {
            store.approve(userId, sessionId, pending.toolName(), pending.fingerprint());
        }
    }

    /** 是否已批准：存的指纹与本次调用参数一致（JsonNode.equals 与 key 顺序无关）。 */
    private boolean approved(ToolCall call, String sessionId, Long userId) {
        String stored = store.fingerprint(userId, sessionId, call.name());
        String current = fingerprint(call.args());
        if (stored == null || current == null) {
            return false;
        }
        try {
            return json.readTree(stored).equals(json.readTree(current));
        } catch (JsonProcessingException e) {
            return false; // 存的数据坏了 → 视为未批准，fail-closed
        }
    }

    /** 参数指纹：Map → JSON 字符串。序列化失败 → null → 无法匹配任何批准 → 拦 */
    private String fingerprint(Map<String, Object> args) {
        try {
            return json.writeValueAsString(json.valueToTree(args));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private boolean isBusinessFailure(String result) {
        return result != null && result.contains("\"success\":false");
    }
}
