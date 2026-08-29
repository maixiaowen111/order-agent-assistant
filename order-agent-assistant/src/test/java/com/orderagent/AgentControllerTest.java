package com.orderagent;

import com.orderagent.langchain4j.LangChain4jWriteTools;
import com.orderagent.langchain4j.ToolProposalGate;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentController 接线验证：两个接口都绑定登录用户。
 *  - /query：必须登录；会话归属校验（无主→绑定自己，别人的→403）
 *  - /approve：必须登录；只能批准自己名下的会话
 *  - 一次「批准」同时放行手写闸门 + LangChain4j 闸门
 * 纯离线：mock AgentLoop / WritePermissionGate / SessionStore，
 * ToolProposalGate 用真实现 + InMemory store + mock OrderSystemApiClient。
 */
class AgentControllerTest {

    private final Long UID = 1L;

    private AgentLoop loop;
    private WritePermissionGate gate;
    private OrderSystemApiClient api;
    private ToolProposalGate proposalGate;
    private SessionStore store;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        loop = mock(AgentLoop.class);
        gate = mock(WritePermissionGate.class);
        api = mock(OrderSystemApiClient.class);
        proposalGate = new ToolProposalGate(
                new InMemoryPendingStore(), new InMemoryApprovalStore(), new LangChain4jWriteTools(api));
        store = mock(SessionStore.class);
        controller = new AgentController(loop, gate, proposalGate, store);
    }

    @AfterEach
    void tearDown() {
        AgentUserContext.clear();  // ThreadLocal 必须清，防串到下一个测试
    }

    private ToolExecutionRequest cancel(String orderNo) {
        return ToolExecutionRequest.builder()
                .id("call_1").name("cancel_order")
                .arguments("{\"orderNo\":\"" + orderNo + "\"}")
                .build();
    }

    // ---------- /query：登录 + 会话归属 ----------

    @Test
    void 未登录_query_401() {
        assertThatThrownBy(() -> controller.query(Map.of("q", "查单", "sessionId", "s1")))
                .isInstanceOfSatisfying(AgentAuthException.class, e -> assertThat(e.status()).isEqualTo(401));
        verify(loop, never()).chat(anyString(), any(), anyString());
    }

    @Test
    void query_新会话_绑定到当前用户再对话() {
        AgentUserContext.set(UID);
        when(store.ownerOf("s1")).thenReturn(null);
        when(loop.chat(eq("s1"), eq(UID), eq("你好"))).thenReturn("{\"answer\":\"你好，有什么可以帮你？\"}");

        Map<String, Object> result = controller.query(Map.of("q", "你好", "sessionId", "s1"));

        verify(store).bindOwner("s1", UID);          // 无主会话 → 钉上当前用户
        verify(loop).chat("s1", UID, "你好");
        assertThat(result).containsEntry("sessionId", "s1")
                .containsEntry("answer", "你好，有什么可以帮你？");
    }

    @Test
    void query_自己的会话_正常对话_不重复绑定() {
        AgentUserContext.set(UID);
        when(store.ownerOf("s1")).thenReturn(UID);
        when(loop.chat(eq("s1"), eq(UID), anyString())).thenReturn("{\"answer\":\"查到了\",\"orderNo\":\"A123\"}");

        Map<String, Object> result = controller.query(Map.of("q", "查一下订单 A123", "sessionId", "s1"));

        verify(store, never()).bindOwner("s1", UID); // 已有归属，不再重复绑定
        assertThat(result).containsEntry("sessionId", "s1")
                .containsEntry("answer", "查到了")
                .containsEntry("orderNo", "A123");
    }

    @Test
    void query_别人的会话_403_不对话() {
        AgentUserContext.set(UID);
        when(store.ownerOf("s1")).thenReturn(2L);    // 会话是用户 2 的

        assertThatThrownBy(() -> controller.query(Map.of("q", "查单", "sessionId", "s1")))
                .isInstanceOfSatisfying(AgentAuthException.class, e -> assertThat(e.status()).isEqualTo(403));
        verify(loop, never()).chat(anyString(), any(), anyString());
    }

    // ---------- /approve：登录 + 只能批准自己的会话 ----------

    @Test
    void 未登录_approve_401() {
        assertThatThrownBy(() -> controller.approve(Map.of("sessionId", "s1")))
                .isInstanceOfSatisfying(AgentAuthException.class, e -> assertThat(e.status()).isEqualTo(401));
        verify(gate, never()).approve(any(), any());
        verify(loop, never()).markApproved(any());
    }

    @Test
    void approve_别人的会话_403_两条路径都不放行() {
        AgentUserContext.set(UID);
        when(store.ownerOf("s1")).thenReturn(2L);    // A 想批准 B 的会话 → 拒绝

        assertThatThrownBy(() -> controller.approve(Map.of("sessionId", "s1")))
                .isInstanceOfSatisfying(AgentAuthException.class, e -> assertThat(e.status()).isEqualTo(403));
        verify(gate, never()).approve(any(), any());
        verify(loop, never()).markApproved(any());
    }

    @Test
    void approve_自己的会话_放行手写闸门和LangChain4j闸门() {
        AgentUserContext.set(UID);
        when(store.ownerOf("s1")).thenReturn(UID);

        Map<String, String> result = controller.approve(Map.of("sessionId", "s1"));

        assertThat(result).containsKey("result");
        verify(gate).approve(UID, "s1");             // 手写路径：按 userId+sessionId 放行
        verify(loop).markApproved("s1");             // 喂给模型"已批准"
        // proposalGate 是真实实现：approveLastBlocked 取 pending（这里没有 → false，无副作用）
    }

    @Test
    void approve_批准LangChain4j最后被拦的提议_同提议可执行() {
        AgentUserContext.set(UID);
        when(store.ownerOf("s1")).thenReturn(UID);

        // LangChain4j 闸门先拦下一次提议（记入 pending，归属用户 1 的会话）
        assertThat(proposalGate.handle(cancel("20260827001"), "s1", UID)).contains("需要人工确认");

        // 用户 1 点「批准」（前端只传 sessionId）
        controller.approve(Map.of("sessionId", "s1"));

        // 批准生效：相同提议可执行
        when(api.cancelOrder("20260827001")).thenReturn("已取消订单 20260827001");
        assertThat(proposalGate.handle(cancel("20260827001"), "s1", UID)).contains("已取消订单");
    }

    @Test
    void approve_批准后模型改参数_仍被拦() {
        AgentUserContext.set(UID);
        when(store.ownerOf("s1")).thenReturn(UID);

        assertThat(proposalGate.handle(cancel("20260827001"), "s1", UID)).contains("需要人工确认");
        controller.approve(Map.of("sessionId", "s1"));

        // 批准的是 A；模型改成 B → 指纹不匹配 → 拦下，B 绝不执行
        when(api.cancelOrder("20260827002")).thenReturn("已取消订单 20260827002");
        String result = proposalGate.handle(cancel("20260827002"), "s1", UID);
        assertThat(result).contains("需要人工确认");
        verify(api, never()).cancelOrder("20260827002");
    }
}
