package com.orderagent.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderagent.Pending;
import com.orderagent.PendingStore;
import com.orderagent.ToolErrors;
import com.orderagent.WriteApprovalStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 任务4：LangChain4j 写工具的权限闸门——"只提建议，批准才执行"。
 *
 * 批准 = 一次性凭证：绑定 (userId, sessionId, toolName, 参数指纹)，存 WriteApprovalStore（Redis），
 * 带 TTL，成功执行后消费。换工具、改参数、换用户、换会话、或过期，一律重新批准。
 * 参数指纹用 Jackson JsonNode（equals 是内容比较，与 JSON key 顺序无关）。
 *
 * 流转：模型提议一次工具调用 → ToolExecutionRequest(name, arguments JSON)
 *       → handle() 对比已批准指纹：
 *           - 未批准 → 返回"需要人工确认"的消息，绝不执行（无副作用），并记住这次提议（pending）
 *           - 已批准且参数完全一致 → 执行；成功则消费批准（一次性）
 * 业务规则（状态机/库存/退款）仍由 order-system 校验，闸门只管"能不能动"。
 *
 * 批准入口有两个：
 *   1) approve(userId, sessionId, proposal) —— 明确指定批准哪次提议（任务4 测试/循环内部用）
 *   2) approveLastBlocked(userId, sessionId) —— 批准"该会话最后一次被拦下的提议"
 *      （AgentController /approve 用，前端只传 sessionId，具体批准什么由这里记住，UI 不用改）
 */
@Component
public class ToolProposalGate {

    /** 本闸门负责的写工具（其他工具走只读通道/未知，一律 fail-closed 不执行） */
    private static final Set<String> WRITE_TOOLS = Set.of("cancel_order", "update_order_address");

    private final LangChain4jWriteTools writeTools;
    private final WriteApprovalStore store;
    private final PendingStore pendingStore;
    private final ObjectMapper json = new ObjectMapper();

    public ToolProposalGate(PendingStore pendingStore, WriteApprovalStore store, LangChain4jWriteTools writeTools) {
        this.pendingStore = pendingStore;
        this.store = store;
        this.writeTools = writeTools;
    }

    /** 人工点「批准」时调用：放行该会话最后被拦下的那一次提议。返回是否有可批准的提议 */
    public boolean approveLastBlocked(Long userId, String sessionId) {
        Pending pending = pendingStore.take(userId, sessionId);
        if (pending == null) {
            return false;
        }
        store.approve(userId, sessionId, pending.toolName(), pending.fingerprint());
        return true;
    }

    /** 人工批准：只批准这一次提议的「工具名 + 参数」，不是整会话所有写操作 */
    public void approve(Long userId, String sessionId, ToolExecutionRequest proposal) {
        store.approve(userId, sessionId, proposal.name(), fingerprint(proposal.arguments()));
    }

    /**
     * 处理模型的一次写工具提议。
     * 返回值是"喂回给模型的工具结果"：未批准是需人工确认的提示，批准后是真实执行结果。
     */
    public String handle(ToolExecutionRequest proposal, String sessionId, Long userId) {
        if (!WRITE_TOOLS.contains(proposal.name())) {
            // 不在本闸门范围内：fail-closed，绝不执行（读工具将来走只读通道，不经过这里）
            return "未知写工具：" + proposal.name();
        }
        if (!approved(proposal, sessionId, userId)) {
            pendingStore.save(userId, sessionId, proposal.name(), fingerprint(proposal.arguments())); // 记住，/approve 批准它
            return blockedReason(proposal);
        }
        String result = execute(proposal);
        // 成功执行后消费批准（一次性）；业务失败保留，允许人工重试
        if (!isBusinessFailure(result)) {
            store.consume(userId, sessionId, proposal.name());
        }
        return result;
    }

    private boolean approved(ToolExecutionRequest proposal, String sessionId, Long userId) {
        JsonNode fp = argsFingerprint(proposal.arguments());
        String stored = store.fingerprint(userId, sessionId, proposal.name());
        if (fp == null || stored == null) {
            return false;
        }
        try {
            return fp.equals(json.readTree(stored));
        } catch (JsonProcessingException e) {
            return false; // 存的数据坏了 → 视为未批准，fail-closed
        }
    }

    private String execute(ToolExecutionRequest proposal) {
        try {
            Map<String, Object> args = parseArgs(proposal.arguments());
            return switch (proposal.name()) {
                case "cancel_order" -> writeTools.cancelOrder(asString(args.get("orderNo")));
                case "update_order_address" -> writeTools.updateOrderAddress(
                        asString(args.get("orderNo")), asString(args.get("address")));
                default -> "未知写工具：" + proposal.name();
            };
        } catch (Exception e) {
            // 兜底：执行层异常不该把堆栈漏给模型
            return ToolErrors.fail("TOOL_ERROR", "工具执行失败，请稍后重试");
        }
    }

    /** 参数指纹：解析成 JsonNode 再序列化成字符串存 Redis。
     *  参数解析不了 → null → 无法匹配任何批准 → fail-closed（拦下） */
    private String fingerprint(String arguments) {
        JsonNode fp = argsFingerprint(arguments);
        return fp == null ? null : fp.toString();
    }

    /** 参数指纹：解析成 JsonNode。JsonNode.equals 是内容比较，与 JSON key 顺序无关。 */
    private JsonNode argsFingerprint(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            return json.readTree(arguments);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Map<String, Object> parseArgs(String arguments) throws JsonProcessingException {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        JsonNode node = json.readTree(arguments);
        return json.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBusinessFailure(String result) {
        return result != null && result.contains("\"success\":false");
    }

    private String blockedReason(ToolExecutionRequest proposal) {
        String orderNo = extractOrderNo(proposal.arguments());
        return orderNo.isEmpty()
                ? "写操作被拦截：" + proposal.name() + " 会修改数据，需要人工确认后才能执行。"
                : "写操作被拦截：" + proposal.name() + " 会修改数据，需要人工确认后才能执行（订单 " + orderNo + "）。";
    }

    private String extractOrderNo(String arguments) {
        try {
            JsonNode node = json.readTree(arguments);
            if (node != null && node.hasNonNull("orderNo") && !node.get("orderNo").asText().isBlank()) {
                return node.get("orderNo").asText();
            }
        } catch (JsonProcessingException ignored) {
        }
        return "";
    }

}
