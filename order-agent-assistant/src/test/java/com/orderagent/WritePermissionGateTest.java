package com.orderagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限闸门测试：只读放行、写操作默认拦、批准后放行（且只对批准过的会话生效）。
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

    private final WritePermissionGate gate = new WritePermissionGate(List.of(QUERY, CANCEL));

    @Test
    void 只读工具总是放行() {
        assertThat(gate.blocks(new ToolCall("c1", "query_order", Map.of()), "s1")).isFalse();
    }

    @Test
    void 未批准的会话_写操作被拦截() {
        assertThat(gate.blocks(new ToolCall("c1", "cancel_order", Map.of("orderNo", "A")), "s1")).isTrue();
    }

    @Test
    void 批准后_该会话写操作放行_其他会话仍拦截() {
        gate.approve("s1");
        assertThat(gate.blocks(new ToolCall("c1", "cancel_order", Map.of()), "s1")).isFalse();
        assertThat(gate.blocks(new ToolCall("c1", "cancel_order", Map.of()), "s2")).isTrue();
    }

    @Test
    void 拦截原因包含订单号和人工确认字样() {
        ToolCall call = new ToolCall("c1", "cancel_order", Map.of("orderNo", "A123"));
        assertThat(gate.reason(call)).contains("A123").contains("人工确认");
    }
}
