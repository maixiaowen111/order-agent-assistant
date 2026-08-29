package com.orderagent;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）兼容层。
 *
 * MCP 工具服务的核心协议就三个方法，和 Tool 接口一一对应：
 *   initialize  —— 握手，告诉客户端「我是谁、支持什么能力」
 *   tools/list  —— 返回 name/description/inputSchema（模型靠它决定用不用、怎么用）
 *   tools/call  —— 执行工具，返回 {content:[{type:"text",text:...}]}
 *
 * 走 Streamable HTTP transport（2025-06-18 规范）：单 POST /mcp 端点，通知回 202、
 * initialize 做协议版本协商。严格客户端（Claude Desktop / Cursor / MCP Inspector）
 * 都能直接连，演示见 MCP_DEMO.md。
 *
 * 为什么手写而不是引 MCP SDK：协议核心就这一个 POST 端点，自己实现看得见本质、
 * 零依赖；将来要接官方 SDK 的 transport，这层业务代码不动。
 *
 * 安全边界：
 *   ① 整个 /mcp 都要登录（Authorization: Bearer <order-system JWT>，见 WebConfig）——
 *      读订单数据绝不能匿名；拦截器把 userId 放进 AgentUserContext，调用链据此带 X-User-Id。
 *   ② tools/list 列出全部工具（含写操作）；tools/call 的写工具走权限闸门，闭环如下：
 *      未批准 → 拦下（isError，正文带 sessionId 和 /approve 入口）→ 人工调 POST /approve 批准
 *      → 模型重试同一操作 → 闸门消费批准真正执行。MCP 层绝不能绕过人工批准去改数据。
 *   ③ 会话管理：每个 MCP 客户端 initialize 时由服务器签发独立会话（Mcp-Session-Id 响应头），
 *      绑定登录用户（见 McpSessionRegistry），后续请求必须随头带回——
 *      每个客户端一个 pending/批准槽互不干扰，/approve 又按绑定用户校验归属，
 *      别人拿你的会话 id 批不了。会话 30 分钟不活跃过期，客户端重新 initialize。
 *
 * 明确不做（规范里对服务器都是可选能力，客户端照常工作）：
 *   SSE 流式响应——规范允许服务器一律回 JSON。
 */
@RestController
public class McpController {

    /** 支持的 MCP 协议版本：客户端请求的版本受支持就原样回声，否则回最新版。 */
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26");

    private final List<Tool> tools;
    private final WritePermissionGate gate;
    private final McpSessionRegistry sessionRegistry;

    public McpController(List<Tool> tools, WritePermissionGate gate, McpSessionRegistry sessionRegistry) {
        this.tools = tools;
        this.gate = gate;
        this.sessionRegistry = sessionRegistry;
    }

    @PostMapping("/mcp")
    public ResponseEntity<Map<String, Object>> mcp(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId,
            HttpServletResponse response) {
        String method = body.get("method") instanceof String ? (String) body.get("method") : null;
        Object id = body.get("id");

        // 通知类消息没有 id、不需要响应：Streamable HTTP 规范要求回 202 Accepted 空 body
        // （如客户端握手完发的 notifications/initialized）
        if (method != null && method.startsWith("notifications/")) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        }
        if (method == null) {
            return ResponseEntity.ok(rpcError(id, -32600, "Invalid Request: missing method"));
        }

        return switch (method) {
            case "initialize" -> handleInitialize(id, params(body), response);
            case "ping" -> ResponseEntity.ok(rpc(id, Map.of()));
            case "tools/list" -> handleToolsList(id, sessionId);
            case "tools/call" -> callTool(id, params(body), sessionId);
            default -> ResponseEntity.ok(rpcError(id, -32601, "Method not found: " + method));
        };
    }

    /**
     * initialize：签发独立 MCP 会话并通过 Mcp-Session-Id 响应头交给客户端。
     * 客户端必须随后续请求带回该头（规范约定），服务器据此区分"是哪个客户端"。
     */
    private ResponseEntity<Map<String, Object>> handleInitialize(Object id, Map<String, Object> params,
                                                                 HttpServletResponse response) {
        Long userId = AgentUserContext.get();
        if (userId == null) {
            return ResponseEntity.ok(rpcError(id, -32001, "未登录：/mcp 需要 Authorization: Bearer <JWT>"));
        }
        String sid = sessionRegistry.create(userId);
        response.setHeader("Mcp-Session-Id", sid);
        return ResponseEntity.ok(rpc(id, initializeResult(params)));
    }

    private ResponseEntity<Map<String, Object>> handleToolsList(Object id, String sessionId) {
        ResponseEntity<Map<String, Object>> sessionErr = requireSession(id, sessionId);
        if (sessionErr != null) {
            return sessionErr;
        }
        return ResponseEntity.ok(rpc(id, Map.of("tools", listTools())));
    }

    /**
     * 除 initialize/通知外的所有方法都必须携带已绑定登录用户的 Mcp-Session-Id：
     *   没带/带错 → 客户端没先握手或会话已过期，返回 JSON-RPC 错误；
     *   带了但绑定的用户 ≠ 当前登录人 → 会话与凭证不匹配，拒绝（防止拿别人的会话干活）。
     * 校验通过时顺带续期（touch），活跃会话不因 30 分钟 TTL 过期。
     * 返回 null = 通过；返回非 null = 应回给客户端的错误响应。
     */
    private ResponseEntity<Map<String, Object>> requireSession(Object id, String sessionId) {
        Long userId = AgentUserContext.get();
        if (userId == null) {
            return ResponseEntity.ok(rpcError(id, -32001, "未登录：/mcp 需要 Authorization: Bearer <JWT>"));
        }
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.ok(rpcError(id, -32001,
                    "会话缺失：请先调用 initialize 获取 Mcp-Session-Id 并随后续请求携带"));
        }
        Long owner = sessionRegistry.touch(sessionId);
        if (owner == null) {
            return ResponseEntity.ok(rpcError(id, -32001, "会话不存在或已过期：请重新 initialize"));
        }
        if (!owner.equals(userId)) {
            return ResponseEntity.ok(rpcError(id, -32001,
                    "会话与当前登录用户不符：请使用该会话绑定的账号重新登录"));
        }
        return null;
    }

    /** initialize 结果：协议版本协商 + 能力声明 + 服务信息 + 使用说明。 */
    private Map<String, Object> initializeResult(Map<String, Object> params) {
        String requested = params.get("protocolVersion") instanceof String
                ? (String) params.get("protocolVersion") : "";
        // 客户端版本受支持就原样回声，否则回我们支持的最新版（客户端自己决定能否降级）
        String version = SUPPORTED_PROTOCOL_VERSIONS.contains(requested)
                ? requested
                : SUPPORTED_PROTOCOL_VERSIONS.get(0);
        return Map.of(
                "protocolVersion", version,
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", "order-agent", "version", "0.0.1"),
                // 规范允许的 instructions：Claude Desktop 会展示给用户，先把「鉴权 + 写要批准」说清楚
                "instructions", "连接本 MCP 服务需要登录：客户端配置里带 Authorization: Bearer <order-system JWT>"
                        + "（先在 order-system 注册/登录拿 token）。该 agent 的写操作需要人工批准：模型触发写工具时"
                        + "会被拦下（isError:true）。批准必须带登录凭证：POST /approve（Authorization: Bearer <token>，"
                        + "body 里带 sessionId），批准后让模型重试。只读查询无需批准，但同样要求登录凭证（Bearer）。");
    }

    /** tools/list：把 Tool 接口的 name/description/inputSchema 原样暴露成 MCP 工具。 */
    private List<Map<String, Object>> listTools() {
        return tools.stream().map(t -> {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("name", t.name());
            spec.put("description", t.description());
            spec.put("inputSchema", t.inputSchema());
            return spec;
        }).toList();
    }

    /** tools/call：只读工具直接执行；写工具走闸门（未批准拦下 / 已批准执行）。 */
    private ResponseEntity<Map<String, Object>> callTool(Object id, Map<String, Object> params, String sessionId) {
        // 会话校验：必须带 initialize 签发的 Mcp-Session-Id 且绑定当前登录用户。
        // 顺带续期；失败返回 JSON-RPC 错误，不执行任何工具（含只读——查订单也不能匿名）。
        ResponseEntity<Map<String, Object>> sessionErr = requireSession(id, sessionId);
        if (sessionErr != null) {
            return sessionErr;
        }
        Long userId = AgentUserContext.get();

        String name = params.get("name") == null ? "" : String.valueOf(params.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?>
                ? (Map<String, Object>) params.get("arguments")
                : Map.of();

        Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return ResponseEntity.ok(rpcError(id, -32602, "Unknown tool: " + name));
        }

        // 写操作：MCP 层也走闸门，防止绕过人工批准直接改数据。
        // 会话语义：用本客户端 initialize 时签发的 Mcp-Session-Id 当 sessionId——
        //   每个客户端一个 pending/批准槽，互不干扰；/approve 按绑定用户校验归属。
        //   未批准 → blocks() 拦下并记住 pending → 返回 isError + 批准入口（含 sessionId）
        //   已批准 → blocks() 原子消费批准返回 false → 真正执行 → afterToolExecuted 通知闸门
        ToolCall call = new ToolCall(String.valueOf(id), name, args);
        if (!tool.readOnly()) {
            if (gate.blocks(call, sessionId, userId)) {
                String text = gate.reason(call)
                        + " 批准入口：POST /approve（Authorization: Bearer <token>），"
                        + "body={\"sessionId\":\"" + sessionId + "\"}，然后让模型重试同一操作。";
                return ResponseEntity.ok(rpc(id, Map.of(
                        "content", List.of(Map.of("type", "text", "text", text)),
                        "isError", true)));
            }
            String result = tool.run(args);
            gate.afterToolExecuted(call, sessionId, userId, result);
            return ResponseEntity.ok(rpc(id, Map.of("content", List.of(Map.of("type", "text", "text", result)))));
        }

        String result = tool.run(args);
        return ResponseEntity.ok(rpc(id, Map.of("content", List.of(Map.of("type", "text", "text", result)))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> params(Map<String, Object> body) {
        return body.get("params") instanceof Map<?, ?>
                ? (Map<String, Object>) body.get("params")
                : Map.of();
    }

    private Map<String, Object> rpc(Object id, Map<String, Object> result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private Map<String, Object> rpcError(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }
}
