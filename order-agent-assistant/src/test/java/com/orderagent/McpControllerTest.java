package com.orderagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 兼容层测试：Streamable HTTP 传输（状态码 / 协议版本协商）+ JSON-RPC 握手、
 * 发现、调用三种方法，以及写工具在 MCP 层也被闸门拦。
 */
class McpControllerTest {

    private Tool readTool;
    private Tool writeTool;
    private WritePermissionGate gate;
    private McpController controller;

    @BeforeEach
    void setUp() {
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

        controller = new McpController(List.of(readTool, writeTool), gate);
    }

    @Test
    void initialize_回声客户端请求的协议版本() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18")));
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
    }

    @Test
    void initialize_旧版本也回声() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-03-26")));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        assertThat(result.get("protocolVersion")).isEqualTo("2025-03-26");
    }

    @Test
    void initialize_未知版本回落最新() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2030-01-01")));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        assertThat(result.get("protocolVersion")).isEqualTo("2025-06-18");
    }

    @Test
    void 通知类方法_回202空body() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(
                Map.of("method", "notifications/initialized"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isNull();
    }

    @Test
    void 缺method_回InvalidRequest() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of("jsonrpc", "2.0", "id", 9));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertThat(error.get("code")).isEqualTo(-32600);
    }

    @Test
    void tools_list_暴露全部工具的name和schema() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
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

    @Test
    void tools_call_只读工具直接执行() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
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

    @Test
    void tools_call_写工具被闸门拦_不执行() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                "params", Map.of("name", "cancel_order",
                        "arguments", Map.of("orderNo", "2026"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        assertThat(result.get("isError")).isEqualTo(true);
        assertThat(String.valueOf(result.get("content"))).contains("写操作被拦截");
        verify(writeTool, never()).run(any());
        verify(gate).reason(any());
    }

    @Test
    void tools_call_未知工具返回JSONRPC错误() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 5, "method", "tools/call",
                "params", Map.of("name", "no_such_tool")));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertThat(error.get("code")).isEqualTo(-32602);
    }

    @Test
    void 未知方法返回MethodNotFound() {
        ResponseEntity<Map<String, Object>> resp = controller.mcp(Map.of(
                "jsonrpc", "2.0", "id", 6, "method", "foo"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertThat(error.get("code")).isEqualTo(-32601);
    }
}
