package com.orderagent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 兼容层测试：Streamable HTTP 传输（状态码 / 协议版本协商）+ JSON-RPC 握手、
 * 发现、调用三种方法，写工具在 MCP 层也被闸门拦，以及未登录不能 tools/call。
 * 会话语义：initialize 签发独立 Mcp-Session-Id（响应头），后续请求必须随头带回，
 * 且绑定用户必须等于当前登录人（见 McpSessionRegistry）。
 * 注：真正拦截未登录请求的是 AgentAuthInterceptor（挂在 /mcp 上，见 WebConfig），
 * 这里测的是 Controller 层的纵深防御 + 登录态下的工具调用。
 */
class McpControllerTest {

    /** 模拟 initialize 时注册表签发的会话 id（真实实现是 mcp-<uuid>） */
    private static final String SID = "mcp-test-1";

    private Tool readTool;
    private Tool writeTool;
    private WritePermissionGate gate;
    private McpSessionRegistry registry;
    private McpController controller;

    @BeforeEach
    void setUp() {
        // tools/call 需要登录身份（防御层要求 AgentUserContext 有值）
        AgentUserContext.set(1L);
        readTool = mock(Tool.class);
        when(readTool.name()).thenReturn("query_product_stock");
        when(readTool.description()).thenReturn("查询商品库存");
        when(readTool.inputSchema()).thenReturn(Map.of("type", "object"));
        when(readTool.readOnly()).thenReturn(true);
        when(readTool.run(any())).thenReturn("商品 1 库存 88 件");

        writeTool = mock(Tool.class);
        when(writeTool.name()).thenReturn("cancel_order");
        when(writeTool.description()).thenReturn("取消订单并退款");
        when(writeTool.inputSchema()).thenReturn(Map.of("type", "object"));
        when(writeTool.readOnly()).thenReturn(false);

        gate = mock(WritePermissionGate.class);
        when(gate.reason(any())).thenReturn("写操作被拦截：需要人工确认后才能执行（订单 2026）。");
        // 默认：写工具被拦（未批准）——每个写工具用例明确覆写 blocks 才放行
        when(gate.blocks(any(), anyString(), any())).thenReturn(true);

        registry = mock(McpSessionRegistry.class);
        when(registry.create(1L)).thenReturn(SID);
        when(registry.touch(SID)).thenReturn(1L);   // 绑定用户 1

        controller = new McpController(List.of(readTool, writeTool), gate, registry);
    }

    @AfterEach
    void tearDown() {
        AgentUserContext.clear();
    }

    /** 模拟一次带会话头的 MCP 请求（真实客户端把 initialize 签发的头随请求带回）。 */
    private ResponseEntity<Map<String, Object>> send(Map<String, Object> body) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        return controller.mcp(body, SID, response);
    }

    // ---------- initialize：协议版本协商 + 签发会话 ----------

    @Test
    void initialize_回声客户端请求的协议版本_并签发McpSessionId() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18")), null, response);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();

        assertThat(resp.getBody().get("id")).isEqualTo(1);
        assertThat(resp.getBody().get("jsonrpc")).isEqualTo("2.0");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        assertThat(result.get("protocolVersion")).isEqualTo("2025-06-18");
        assertThat((Map<String, Object>) result.get("serverInfo")).containsEntry("name", "order-agent");
        assertThat((Map<String, Object>) result.get("capabilities")).containsKey("tools");
        // 规范允许的 instructions：把「写要批准」的使用说明带给客户端
        assertThat(String.valueOf(result.get("instructions"))).contains("人工批准");

        // 服务器签发独立会话并通过响应头带回，客户端后续请求必须随头带回
        verify(registry).create(1L);
        assertThat(response.getHeader("Mcp-Session-Id")).isEqualTo(SID);
    }

    @Test
    void initialize_旧版本也回声() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-03-26")), null, new MockHttpServletResponse());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        assertThat(result.get("protocolVersion")).isEqualTo("2025-03-26");
    }

    @Test
    void initialize_未知版本回落最新() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2030-01-01")), null, new MockHttpServletResponse());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        assertThat(result.get("protocolVersion")).isEqualTo("2025-06-18");
    }

    @Test
    void 通知类方法_回202空body() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(
                Map.of("method", "notifications/initialized"), null, new MockHttpServletResponse());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isNull();
    }

    @Test
    void 缺method_回InvalidRequest() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of("jsonrpc", "2.0", "id", 9), SID,
                new MockHttpServletResponse());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertThat(error.get("code")).isEqualTo(-32600);
    }

    @Test
    void tools_list_暴露全部工具的name和schema() {
        ResponseEntity<Map<String, Object>> resp = send(Map.of(
                "jsonrpc", "2.0", "id", 2, "method", "tools/list"));
        @SuppressWarnings("unchecked")
        List<?> tools = (List<?>) ((Map<String, Object>) resp.getBody().get("result")).get("tools");
        assertThat(tools).hasSize(2);
        assertThat(tools).anySatisfy(t -> {
            assertThat((Map<String, Object>) t).containsEntry("name", "query_product_stock");
            assertThat((Map<String, Object>) t).containsEntry("description", "查询商品库存");
            assertThat((Map<String, Object>) t).containsKey("inputSchema");
        });
        assertThat(tools).anySatisfy(t -> assertThat((Map<String, Object>) t).containsEntry("name", "cancel_order"));
    }

    // ---------- 会话校验：没有有效 Mcp-Session-Id 一切免谈 ----------

    @Test
    void tools_call_未登录_返回未登录错误() {
        AgentUserContext.clear();
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "query_product_stock", "arguments", Map.of("productId", 1))),
                SID, new MockHttpServletResponse());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertThat(error.get("code")).isEqualTo(-32001);
        assertThat(String.valueOf(error.get("message"))).contains("未登录");
        // 没身份绝不执行工具：连只读工具也不行（否则匿名能查订单）
        verify(readTool, never()).run(any());
        verify(registry, never()).touch(anyString());
    }

    @Test
    void tools_call_没带会话头_报会话缺失_不执行() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "query_product_stock", "arguments", Map.of("productId", 1))),
                null, new MockHttpServletResponse());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertThat(error.get("code")).isEqualTo(-32001);
        assertThat(String.valueOf(error.get("message"))).contains("会话缺失");
        verify(readTool, never()).run(any());
    }

    @Test
    void tools_call_会话不在注册表_报会话过期_不执行() {
        when(registry.touch("mcp-expired")).thenReturn(null);   // 会话不存在/30 分钟没活动过期
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "query_product_stock", "arguments", Map.of("productId", 1))),
                "mcp-expired", new MockHttpServletResponse());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertThat(error.get("code")).isEqualTo(-32001);
        assertThat(String.valueOf(error.get("message"))).contains("过期");
        verify(readTool, never()).run(any());
    }

    @Test
    void tools_call_会话绑定的是别人_报不符_不执行() {
        when(registry.touch("mcp-other")).thenReturn(2L);   // 该会话是用户 2 的
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "query_product_stock", "arguments", Map.of("productId", 1))),
                "mcp-other", new MockHttpServletResponse());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertThat(error.get("code")).isEqualTo(-32001);
        assertThat(String.valueOf(error.get("message"))).contains("不符");
        verify(readTool, never()).run(any());
    }

    @Test
    void tools_call_只读工具直接执行() {
        ResponseEntity<Map<String, Object>> resp = send(Map.of(
                "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "query_product_stock",
                        "arguments", Map.of("productId", 1))));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        assertThat(result).doesNotContainKey("isError");
        assertThat(String.valueOf(result.get("content"))).contains("库存 88 件");
        verify(readTool).run(Map.of("productId", 1));
    }

    // ---------- 写工具：走闸门，批准入口带真实会话 id ----------

    @Test
    void tools_call_写工具被闸门拦_不执行_带批准入口() {
        ResponseEntity<Map<String, Object>> resp = send(Map.of(
                "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                "params", Map.of("name", "cancel_order",
                        "arguments", Map.of("orderNo", "2026"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        assertThat(result.get("isError")).isEqualTo(true);
        assertThat(String.valueOf(result.get("content"))).contains("写操作被拦截");
        // 闭环：拦下时不只给原因，还给 /approve 入口和本客户端的 MCP 会话 sessionId
        assertThat(String.valueOf(result.get("content")))
                .contains("/approve")
                .contains(SID);
        verify(writeTool, never()).run(any());
        verify(gate).reason(any());
        // 写操作按本客户端的独立会话走闸门：绑定用户是 1，别人拿别的会话批不了
        verify(gate).blocks(any(), eq(SID), eq(1L));
    }

    @Test
    void tools_call_写工具已批准_真正执行并通知闸门() {
        when(gate.blocks(any(), anyString(), any())).thenReturn(false);  // 已批准 → 放行
        when(writeTool.run(any())).thenReturn("已取消订单 2026");
        ResponseEntity<Map<String, Object>> resp = send(Map.of(
                "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                "params", Map.of("name", "cancel_order",
                        "arguments", Map.of("orderNo", "2026"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        assertThat(result).doesNotContainKey("isError");   // 批准后正常执行，不是错误
        assertThat(String.valueOf(result.get("content"))).contains("已取消订单");
        verify(writeTool).run(Map.of("orderNo", "2026"));
        // 一次性批准执行完要通知闸门消费，防一次批准反复复用
        verify(gate).afterToolExecuted(any(), eq(SID), eq(1L), eq("已取消订单 2026"));
    }

    @Test
    void tools_call_未知工具返回JSONRPC错误() {
        ResponseEntity<Map<String, Object>> resp = send(Map.of(
                "jsonrpc", "2.0", "id", 5, "method", "tools/call",
                "params", Map.of("name", "no_such_tool")));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertThat(error.get("code")).isEqualTo(-32602);
    }

    @Test
    void 未知方法返回MethodNotFound() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 6, "method", "foo"), SID, new MockHttpServletResponse());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertThat(error.get("code")).isEqualTo(-32601);
    }
}
