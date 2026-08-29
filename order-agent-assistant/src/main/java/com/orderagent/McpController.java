package com.orderagent;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 *   ② tools/list 列出全部工具（含写操作），但 tools/call 里写工具**同样被权限闸门拦截**——
 *      MCP 层不能绕过人工批准去改数据。
 *
 * 明确不做（规范里对服务器都是可选能力，客户端照常工作）：
 *   会话管理（Mcp-Session-Id 头）——保持无状态；
 *   SSE 流式响应——规范允许服务器一律回 JSON。
 */
@RestController
public class McpController {

    /** 支持的 MCP 协议版本：客户端请求的版本受支持就原样回声，否则回最新版。 */
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26");

    private final List<Tool> tools;
    private final WritePermissionGate gate;

    public McpController(List<Tool> tools, WritePermissionGate gate) {
        this.tools = tools;
        this.gate = gate;
    }

    @PostMapping("/mcp")
    public ResponseEntity<Map<String, Object>> mcp(@RequestBody Map<String, Object> body) {
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
            case "initialize" -> ResponseEntity.ok(rpc(id, initializeResult(params(body))));
            case "ping" -> ResponseEntity.ok(rpc(id, Map.of()));
            case "tools/list" -> ResponseEntity.ok(rpc(id, Map.of("tools", listTools())));
            case "tools/call" -> callTool(id, params(body));
            default -> ResponseEntity.ok(rpcError(id, -32601, "Method not found: " + method));
        };
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

    /** tools/call：只读工具直接执行；写工具被闸门拦截，绝不执行。 */
    private ResponseEntity<Map<String, Object>> callTool(Object id, Map<String, Object> params) {
        // 纵深防御：拦截器已保证 /mcp 有 Bearer token，这里再确认用户身份在上下文中。
        // 工具调用链（OrderSystemApiClient）靠 AgentUserContext 决定带不带 X-User-Id——
        // 没有它，内部接口查订单会因缺少用户身份被拒。
        if (AgentUserContext.get() == null) {
            return ResponseEntity.ok(rpcError(id, -32001, "未登录：/mcp 需要 Authorization: Bearer <JWT>"));
        }

        String name = params.get("name") == null ? "" : String.valueOf(params.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?>
                ? (Map<String, Object>) params.get("arguments")
                : Map.of();

        Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return ResponseEntity.ok(rpcError(id, -32602, "Unknown tool: " + name));
        }

        // 写操作：MCP 层也走闸门，防止绕过人工批准直接改数据
        ToolCall call = new ToolCall(String.valueOf(id), name, args);
        if (!tool.readOnly()) {
            return ResponseEntity.ok(rpc(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", gate.reason(call))),
                    "isError", true)));
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
