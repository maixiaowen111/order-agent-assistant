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
 * 严格 MCP 客户端握手模拟：按 TypeScript SDK 客户端（Claude Desktop / Cursor 底层）
 * 的真实顺序，逐步断言 HTTP 状态码和 JSON-RPC body。证明 /mcp 能过严格客户端握手。
 *
 * 注册 4 个工具镜像真实应用：2 个只读（query_product_stock / query_order）
 * + 2 个写（update_order_address / cancel_order）。
 */
class McpHandshakeTest {

    private Tool queryStock;
    private Tool queryOrder;
    private Tool updateAddress;
    private Tool cancelOrder;
    private WritePermissionGate gate;
    private McpController controller;

    @BeforeEach
    void setUp() {
        queryStock = tool("query_product_stock", "查询商品库存", true);
        queryOrder = tool("query_order", "查询订单与收货信息", true);
        updateAddress = tool("update_order_address", "修改订单收货地址", false);
        cancelOrder = tool("cancel_order", "取消订单并退款", false);
        when(queryStock.run(any())).thenReturn("商品 1 库存 88 件");

        gate = mock(WritePermissionGate.class);
        when(gate.reason(any())).thenReturn("写操作被拦截：需要人工确认后才能执行。");

        controller = new McpController(List.of(queryStock, queryOrder, updateAddress, cancelOrder), gate);
    }

    private static Tool tool(String name, String desc, boolean readOnly) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.description()).thenReturn(desc);
        when(t.inputSchema()).thenReturn(Map.of("type", "object"));
        when(t.readOnly()).thenReturn(readOnly);
        return t;
    }

    /** 客户端发一个 JSON-RPC 请求，返回服务器响应（内部复用 controller.mcp）。 */
    private ResponseEntity<Map<String, Object>> send(Map<String, Object> msg) {
        return controller.mcp(msg);
    }

    @Test
    void 完整握手_严格客户端可连接() {
        // 1. initialize：客户端宣告协议版本 2025-06-18
        ResponseEntity<Map<String, Object>> init = send(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2025-06-18",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "claude-desktop", "version", "0.0.1"))));
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> initResult = (Map<String, Object>) init.getBody().get("result");
        assertThat(initResult.get("protocolVersion")).isEqualTo("2025-06-18");
        assertThat((Map<String, Object>) initResult.get("capabilities")).containsKey("tools");
        assertThat((Map<String, Object>) initResult.get("serverInfo")).containsEntry("name", "order-agent");

        // 2. 握手完发初始化完成通知 → 规范要求 202 Accepted 空 body
        ResponseEntity<Map<String, Object>> ack = send(Map.of("method", "notifications/initialized"));
        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ack.getBody()).isNull();

        // 3. tools/list：发现 4 个工具，每个带 name/description/inputSchema（模型据此决定用不用）
        ResponseEntity<Map<String, Object>> list = send(Map.of(
                "jsonrpc", "2.0", "id", 2, "method", "tools/list"));
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<?> tools = (List<?>) ((Map<String, Object>) list.getBody().get("result")).get("tools");
        assertThat(tools).hasSize(4);
        assertThat(tools).extracting(t -> (String) ((Map<String, Object>) t).get("name"))
                .containsExactlyInAnyOrder("query_product_stock", "query_order", "update_order_address", "cancel_order");
        assertThat(tools).allSatisfy(t -> {
            Map<String, Object> spec = (Map<String, Object>) t;
            assertThat(spec).containsKeys("name", "description", "inputSchema");
        });

        // 4. tools/call 只读工具 → 200 + content 结果
        ResponseEntity<Map<String, Object>> call = send(Map.of(
                "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "query_product_stock", "arguments", Map.of("productId", 1))));
        assertThat(call.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> callResult = (Map<String, Object>) call.getBody().get("result");
        assertThat(callResult).doesNotContainKey("isError");
        assertThat(String.valueOf(callResult.get("content"))).contains("库存 88 件");

        // 5. tools/call 写工具 → isError:true 被闸门拦，run 绝不执行（MCP 层绕不过人工批准）
        ResponseEntity<Map<String, Object>> write = send(Map.of(
                "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                "params", Map.of("name", "update_order_address",
                        "arguments", Map.of("orderNo", "2026", "address", "上海市浦东新区"))));
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> writeResult = (Map<String, Object>) write.getBody().get("result");
        assertThat(writeResult.get("isError")).isEqualTo(true);
        assertThat(String.valueOf(writeResult.get("content"))).contains("写操作被拦截");
        verify(updateAddress, never()).run(any());
        verify(cancelOrder, never()).run(any());
        verify(gate).reason(any());
    }
}
