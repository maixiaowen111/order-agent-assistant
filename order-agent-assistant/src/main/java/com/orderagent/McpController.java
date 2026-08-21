package com.orderagent;

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
 * 为什么手写而不是引 MCP SDK：协议核心就这一个 POST 端点，自己实现看得见本质、
 * 零依赖；将来要接 Claude Desktop / Cursor 等严格客户端，直接换成官方 SDK 的
 * transport，这层业务代码不动。
 *
 * 安全边界：tools/list 列出全部工具（含写操作），但 tools/call 里写工具**同样被
 * 权限闸门拦截**——MCP 层不能绕过人工批准去改数据。
 */
@RestController
public class McpController {

    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final List<Tool> tools;
    private final WritePermissionGate gate;

    public McpController(List<Tool> tools, WritePermissionGate gate) {
        this.tools = tools;
        this.gate = gate;
    }

    @PostMapping("/mcp")
    public Object mcp(@RequestBody Map<String, Object> body) {
        String method = (String) body.get("method");
        Object id = body.get("id");

        // 通知类消息没有 id、不需要响应（比如客户端握手完发的 notifications/initialized）
        if (method != null && method.startsWith("notifications/")) {
            return null;
        }

        switch (method == null ? "" : method) {
            case "initialize" -> {
                return rpc(id, Map.of(
                        "protocolVersion", PROTOCOL_VERSION,
                        "capabilities", Map.of("tools", Map.of("listChanged", false)),
                        "serverInfo", Map.of("name", "order-agent", "version", "0.0.1")));
            }
            case "ping" -> {
                return rpc(id, Map.of());
            }
            case "tools/list" -> {
                return rpc(id, Map.of("tools", listTools()));
            }
            case "tools/call" -> {
                return callTool(id, params(body));
            }
            default -> {
                return rpcError(id, -32601, "Method not found: " + method);
            }
        }
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
    private Object callTool(Object id, Map<String, Object> params) {
        String name = params.get("name") == null ? "" : String.valueOf(params.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?>
                ? (Map<String, Object>) params.get("arguments")
                : Map.of();

        Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return rpcError(id, -32602, "Unknown tool: " + name);
        }

        // 写操作：MCP 层也走闸门，防止绕过人工批准直接改数据
        ToolCall call = new ToolCall(String.valueOf(id), name, args);
        if (!tool.readOnly()) {
            return rpc(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", gate.reason(call))),
                    "isError", true));
        }

        String result = tool.run(args);
        return rpc(id, Map.of("content", List.of(Map.of("type", "text", "text", result))));
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
