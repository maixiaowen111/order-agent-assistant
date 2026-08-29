package com.orderagent.langchain4j;

import com.orderagent.InMemoryApprovalStore;
import com.orderagent.InMemoryPendingStore;
import com.orderagent.OrderSystemApiClient;
import com.orderagent.PendingStore;
import com.orderagent.WriteApprovalStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务4 验收：LangChain4j 只"提建议"，ToolProposalGate 决定，未批准绝不执行。
 *   未批准无副作用 / 批准执行 / 参数篡改被拦 / 换工具被拦 / key 顺序无关 / 一次性消费 /
 *   绑定用户 / 多实例共享批准。
 * 纯离线：mock OrderSystemApiClient，批准凭证用内存实现，空机器能跑绿。
 */
class ToolProposalGateTest {

    private final OrderSystemApiClient api = mock(OrderSystemApiClient.class);
    private final WriteApprovalStore store = new InMemoryApprovalStore();
    private final ToolProposalGate gate =
            new ToolProposalGate(new InMemoryPendingStore(), store, new LangChain4jWriteTools(api));

    private final Long UID = 1L;

    private ToolExecutionRequest cancel(String orderNo) {
        return ToolExecutionRequest.builder()
                .id("call_1")
                .name("cancel_order")
                .arguments("{\"orderNo\":\"" + orderNo + "\"}")
                .build();
    }

    private ToolExecutionRequest updateAddress(String orderNo, String address) {
        return ToolExecutionRequest.builder()
                .id("call_2")
                .name("update_order_address")
                .arguments("{\"orderNo\":\"" + orderNo + "\",\"address\":\"" + address + "\"}")
                .build();
    }

    @Test
    void 未批准_不执行_返回需人工确认() {
        String result = gate.handle(cancel("20260827001"), "s1", UID);

        assertThat(result).contains("需要人工确认");
        verify(api, never()).cancelOrder(any());   // 关键：无副作用
    }

    @Test
    void 批准后同参数_才执行() {
        gate.approve(UID, "s1", cancel("20260827001"));
        when(api.cancelOrder("20260827001")).thenReturn("已取消订单 20260827001");

        String result = gate.handle(cancel("20260827001"), "s1", UID);

        assertThat(result).contains("已取消订单");
        verify(api).cancelOrder("20260827001");
    }

    @Test
    void 参数被篡改_需重新批准() {
        gate.approve(UID, "s1", cancel("20260827001"));  // 人批准的是订单 A
        when(api.cancelOrder("20260827002")).thenReturn("已取消订单 20260827002");

        String result = gate.handle(cancel("20260827002"), "s1", UID);  // 模型提议换成订单 B

        assertThat(result).contains("需要人工确认");
        verify(api, never()).cancelOrder("20260827002");
    }

    @Test
    void 换工具_需重新批准() {
        gate.approve(UID, "s1", cancel("20260827001"));

        String result = gate.handle(updateAddress("20260827001", "上海市浦东新区"), "s1", UID);

        assertThat(result).contains("需要人工确认");
        verify(api, never()).updateOrderAddress(any(), any());
    }

    @Test
    void 参数key顺序不同_内容一致_视为已批准() {
        gate.approve(UID, "s1", ToolExecutionRequest.builder().id("x").name("update_order_address")
                .arguments("{\"orderNo\":\"N1\",\"address\":\"上海\"}").build());
        when(api.updateOrderAddress("N1", "上海")).thenReturn("已更新订单 N1 的收货地址为：上海");

        // 模型这次把 JSON key 顺序反过来了：JsonNode.equals 是内容比较，应照样放行
        String result = gate.handle(ToolExecutionRequest.builder().id("y").name("update_order_address")
                .arguments("{\"address\":\"上海\",\"orderNo\":\"N1\"}").build(), "s1", UID);

        assertThat(result).doesNotContain("需要人工确认");
        verify(api).updateOrderAddress("N1", "上海");
    }

    @Test
    void 未知工具_fail_closed_不执行() {
        String result = gate.handle(ToolExecutionRequest.builder().id("c").name("query_order")
                .arguments("{\"orderNo\":\"1\"}").build(), "s1", UID);

        assertThat(result).contains("未知写工具");
        verify(api, never()).cancelOrder(any());
        verify(api, never()).updateOrderAddress(any(), any());
    }

    @Test
    void 写工具定义_能被LangChain4j生成Spec() {
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(LangChain4jWriteTools.class);

        assertThat(specs).extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("cancel_order", "update_order_address");
        for (ToolSpecification spec : specs) {
            assertThat(spec.description()).isNotBlank();
            assertThat(spec.parameters()).isNotNull();
        }
    }

    @Test
    void 被拦后approveLastBlocked_相同提议可执行() {
        // /approve 的接线：拦下一次提议 → 记入 pending → approveLastBlocked 放行它
        assertThat(gate.handle(cancel("20260827001"), "s1", UID)).contains("需要人工确认");

        assertThat(gate.approveLastBlocked(UID, "s1")).isTrue();

        when(api.cancelOrder("20260827001")).thenReturn("已取消订单 20260827001");
        assertThat(gate.handle(cancel("20260827001"), "s1", UID)).contains("已取消订单");
    }

    @Test
    void 无pending时approveLastBlocked_返回false() {
        assertThat(gate.approveLastBlocked(UID, "s1")).isFalse();
    }

    @Test
    void 批准是一次性的_成功后消费_再次同提议被拦() {
        gate.approve(UID, "s1", cancel("20260827001"));
        when(api.cancelOrder("20260827001")).thenReturn("已取消订单 20260827001");

        assertThat(gate.handle(cancel("20260827001"), "s1", UID)).contains("已取消订单");  // 执行成功 → 消费

        when(api.cancelOrder("20260827001")).thenReturn("已取消订单 20260827001");
        assertThat(gate.handle(cancel("20260827001"), "s1", UID)).contains("需要人工确认"); // 批准已消费 → 再拦
    }

    @Test
    void 业务失败_不消费批准_允许人工重试() {
        gate.approve(UID, "s1", cancel("20260827001"));
        when(api.cancelOrder("20260827001"))
                .thenReturn("{\"success\":false,\"errorCode\":\"BUSINESS_ERROR\",\"message\":\"订单不存在\"}");

        assertThat(gate.handle(cancel("20260827001"), "s1", UID)).contains("订单不存在");  // 业务失败

        when(api.cancelOrder("20260827001")).thenReturn("已取消订单 20260827001");
        assertThat(gate.handle(cancel("20260827001"), "s1", UID)).contains("已取消订单");   // 保留批准 → 重试放行
    }

    @Test
    void 批准绑定用户_其他用户不能借批准执行() {
        gate.approve(UID, "s1", cancel("20260827001"));
        when(api.cancelOrder("20260827001")).thenReturn("已取消订单 20260827001");

        // 用户 2 用同一 sessionId 发起同样调用 → 拿不到用户 1 的批准
        String result = gate.handle(cancel("20260827001"), "s1", 2L);

        assertThat(result).contains("需要人工确认");
        verify(api, never()).cancelOrder("20260827001");
    }

    @Test
    void 多实例共享批准_一个实例批准另一个实例执行() {
        // 模拟 Redis：pending（被拦下的提议）和批准凭证都存一处，两个实例共享读写
        PendingStore sharedPending = new InMemoryPendingStore();
        WriteApprovalStore shared = new InMemoryApprovalStore();
        ToolProposalGate instanceA = new ToolProposalGate(sharedPending, shared, new LangChain4jWriteTools(api));
        ToolProposalGate instanceB = new ToolProposalGate(sharedPending, shared, new LangChain4jWriteTools(api));

        // A 实例拦下一次提议（pending 写进共享 store）
        assertThat(instanceA.handle(cancel("20260827001"), "s1", UID)).contains("需要人工确认");

        // B 实例批准（模拟 /approve 落在另一台实例，能读到 A 拦下的提议）
        assertThat(instanceB.approveLastBlocked(UID, "s1")).isTrue();

        // A 实例再执行同一提议 → 放行（批准在共享 store 里，两边都看得见）
        when(api.cancelOrder("20260827001")).thenReturn("已取消订单 20260827001");
        assertThat(instanceA.handle(cancel("20260827001"), "s1", UID)).contains("已取消订单");
    }
}
