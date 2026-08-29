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
 * 严格 MCP 客户端握手模拟：按 TypeScript SDK 客户端（Claude Desktop / Cursor 底层）
 * 的真实顺序，逐步断言 HTTP 状态码和 JSON-RPC body。证明 /mcp 能过严格客户端握手。
 *
 * 会话行为：initialize 时服务器签发独立 Mcp-Session-Id（响应头），严格客户端把它存下、
 * 随后续每个请求带回——这里忠实模拟这一步，并断言服务器按绑定用户放行。
 *
 * 注册 4 个工具镜像真实应用：2 个只读（query_product_stock / query_order）
 * + 2 个写（update_order_address / cancel_order）。
 * 整个 /mcp 都要登录：AgentUserContext 里先放好身份，模拟拦截器已从 Bearer JWT 解析出 userId。
 */
class McpHandshakeTest {

    private Tool queryStock;
    private Tool queryOrder;
    private Tool updateAddress;
    private Tool cancelOrder;
    private WritePermissionGate gate;
    private McpSessionRegistry registry;
    private McpController controller;

    @BeforeEach
    void setUp() {
        // /mcp 已加登录要求：拦截器从 Bearer JWT 解析出 userId 放进 AgentUserContext，
        // 这里预置身份，等价于真实请求过了一遍 AgentAuthInterceptor。
        AgentUserContext.set(1L);
        queryStock = tool("query_product_stock", "查询商品库存", true);
        queryOrder = tool("query_order", "查询订单与收货信息", true);
        updateAddress = tool("update_order_address", "修改订单收货地址", false);
        cancelOrder = tool("cancel_order", "取消订单并退款", false);
        when(queryStock.run(any())).thenReturn("商品 1 库存 88 件");

        gate = mock(WritePermissionGate.class);
        when(gate.reason(any())).thenReturn("写操作被拦截：需要人工确认后才能执行。");
        // 默认：写工具被拦（未批准）——"批准后重试"用例明确覆写 blocks 才放行
        when(gate.blocks(any(), anyString(), any())).thenReturn(true);

        registry = mock(McpSessionRegistry.class);
        when(registry.create(1L)).thenReturn("mcp-handshake-1");
        when(registry.touch("mcp-handshake-1")).thenReturn(1L);

        controller = new McpController(List.of(queryStock, queryOrder, updateAddress, cancelOrder), gate, registry);
    }

    @AfterEach
    void tearDown() {
        AgentUserContext.clear();
    }

    private static Tool tool(String name, String desc, boolean readOnly) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.description()).thenReturn(desc);
        when(t.inputSchema()).thenReturn(Map.of("type", "object"));
        when(t.readOnly()).thenReturn(readOnly);
        return t;
    }

    /** 模拟客户端：持有一个会话 id（initialize 后填充），每次请求随头带回。 */
    private String sid;

    private ResponseEntity<Map<String, Object>> send(Map<String, Object> msg) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseEntity<Map<String, Object>> resp = controller.mcp(msg, sid, response);
        String issued = response.getHeader("Mcp-Session-Id");
        if (issued != null) {
            // 服务器在 initialize 响应头里签发的会话 id，客户端存下并随后续请求带回
            sid = issued;
        }
        return resp;
    }

    @Test
    void 完整握手_严格客户端可连接() {
        // 1. initialize：客户端宣告协议版本 2025-06-18，服务器签发会话
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
        // 服务器签发了独立会话，客户端存下了它
        assertThat(sid).isEqualTo("mcp-handshake-1");
        verify(registry).create(1L);

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
        // 服务器按会话校验了绑定用户并续期
        verify(registry).touch("mcp-handshake-1");

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
        // 拦下时带批准入口和本客户端会话 sessionId（客户端拿它去 /approve）
        assertThat(String.valueOf(writeResult.get("content"))).contains("/approve").contains("mcp-handshake-1");
        verify(updateAddress, never()).run(any());
        verify(cancelOrder, never()).run(any());
        verify(gate).reason(any());
        verify(gate).blocks(any(), eq("mcp-handshake-1"), eq(1L));
    }

    @Test
    void 写工具批准后_重试同参数_真正执行并通知闸门() {
        // 严格客户端：先 initialize 拿到会话，再发 tools/call
        send(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18")));
        assertThat(sid).isEqualTo("mcp-handshake-1");

        when(gate.blocks(any(), anyString(), any())).thenReturn(false);  // 已批准 → 放行
        when(updateAddress.run(any())).thenReturn("订单 2026 收货地址已更新为上海市浦东新区");

        ResponseEntity<Map<String, Object>> write = send(Map.of(
                "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                "params", Map.of("name", "update_order_address",
                        "arguments", Map.of("orderNo", "2026", "address", "上海市浦东新区"))));

        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> writeResult = (Map<String, Object>) write.getBody().get("result");
        assertThat(writeResult).doesNotContainKey("isError");   // 批准后正常执行，不是错误
        assertThat(String.valueOf(writeResult.get("content"))).contains("地址已更新");
        verify(updateAddress).run(Map.of("orderNo", "2026", "address", "上海市浦东新区"));
        // 一次性批准执行完要通知闸门消费（防一次批准反复复用）
        verify(gate).afterToolExecuted(any(), eq("mcp-handshake-1"), eq(1L),
                eq("订单 2026 收货地址已更新为上海市浦东新区"));
    }
}
