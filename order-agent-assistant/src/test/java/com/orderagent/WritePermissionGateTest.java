package com.orderagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限闸门测试：只读放行、写操作默认拦、一次性批准——
 * 批准绑定 (userId, sessionId, toolName, 参数指纹)，成功执行后消费；
 * 换用户/换会话/换参数都拦；业务失败不消费、允许人工重试。
 */
class WritePermissionGateTest {

    private static final Tool QUERY = new Tool() {
        public String name() { return "query_order"; }
        public String description() { return "查询订单"; }
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        public String run(Map<String, Object> args) { return "ok"; }
    };

    private static final Tool CANCEL = new Tool() {
        public String name() { return "cancel_order"; }
        public String description() { return "取消订单"; }
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        public String run(Map<String, Object> args) { return "ok"; }
        public boolean readOnly() { return false; }   // 写操作工具
    };

    private final WriteApprovalStore store = new InMemoryApprovalStore();
    private final WritePermissionGate gate =
            new WritePermissionGate(new InMemoryPendingStore(), store, List.of(QUERY, CANCEL));

    private ToolCall cancel(String orderNo) {
        return new ToolCall("c1", "cancel_order", Map.of("orderNo", orderNo));
    }

    @Test
    void 只读工具总是放行() {
        assertThat(gate.blocks(new ToolCall("c1", "query_order", Map.of()), "s1", 1L)).isFalse();
    }

    @Test
    void 未批准的会话_写操作被拦截() {
        assertThat(gate.blocks(cancel("A"), "s1", 1L)).isTrue();
    }

    @Test
    void 批准后_仅同用户同会话同参数放行() {
        assertThat(gate.blocks(cancel("A"), "s1", 1L)).isTrue();   // 先拦一次，记入 pending

        gate.approve(1L, "s1");                                    // 人工批准

        assertThat(gate.blocks(cancel("A"), "s1", 1L)).isFalse();  // 同用户/会话/参数 → 放行
        assertThat(gate.blocks(cancel("B"), "s1", 1L)).isTrue();   // 参数不同 → 拦（模型换订单号）
        assertThat(gate.blocks(cancel("A"), "s2", 1L)).isTrue();   // 会话不同 → 拦
        assertThat(gate.blocks(cancel("A"), "s1", 2L)).isTrue();   // 用户不同 → 拦
    }

    @Test
    void 批准是一次性的_成功后消费_再次同参数调用被拦() {
        assertThat(gate.blocks(cancel("A"), "s1", 1L)).isTrue();
        gate.approve(1L, "s1");

        assertThat(gate.blocks(cancel("A"), "s1", 1L)).isFalse();         // 放行并执行
        gate.afterToolExecuted(cancel("A"), "s1", 1L, "已取消订单 A");     // 执行成功

        assertThat(gate.blocks(cancel("A"), "s1", 1L)).isTrue();           // 批准已消费 → 再拦，须重新批准
    }

    @Test
    void 业务失败_不消费批准_允许人工重试() {
        assertThat(gate.blocks(cancel("A"), "s1", 1L)).isTrue();
        gate.approve(1L, "s1");

        assertThat(gate.blocks(cancel("A"), "s1", 1L)).isFalse();
        gate.afterToolExecuted(cancel("A"), "s1", 1L,
                "{\"success\":false,\"errorCode\":\"BUSINESS_ERROR\",\"message\":\"订单不存在\"}");  // 业务失败

        assertThat(gate.blocks(cancel("A"), "s1", 1L)).isFalse();          // 保留批准 → 仍放行可重试
    }

    @Test
    void 批准绑定用户_其他用户不能借批准执行() {
        assertThat(gate.blocks(cancel("A"), "s1", 1L)).isTrue();
        gate.approve(1L, "s1");

        assertThat(gate.blocks(cancel("A"), "s1", 2L)).isTrue();           // 用户 2 拿不到用户 1 的批准
    }

    @Test
    void 拦截原因包含订单号和人工确认字样() {
        assertThat(gate.reason(cancel("A123"))).contains("A123").contains("人工确认");
    }
}
