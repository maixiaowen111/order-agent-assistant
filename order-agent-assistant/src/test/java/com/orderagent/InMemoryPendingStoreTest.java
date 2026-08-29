package com.orderagent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pending 存储语义测试（用内存实现跑真逻辑，与 RedisPendingStore 同契约）：
 *   - 先拦下的优先：同一会话已有未批准的提议时，新提议不覆盖（MCP 固定 mcp-{userId} 槽位
 *     上连续拦下两个不同操作，批准取走的永远是第一个，后一个不会被静默顶掉）。
 *   - take 取走即清：批准一次对应一次提议，取走后可再记新的。
 */
class InMemoryPendingStoreTest {

    private final InMemoryPendingStore store = new InMemoryPendingStore();

    @Test
    void 连续拦下两个不同提议_只保留第一个_批准的是先拦的() {
        store.save(1L, "mcp-1", "cancel_order", "{\"orderNo\":\"A\"}");
        store.save(1L, "mcp-1", "update_order_address", "{\"orderNo\":\"A\",\"address\":\"B\"}");

        // 人点批准 → 取走的是第一个被拦下的 cancel_order（人看到的那个），不是后一个
        Pending taken = store.take(1L, "mcp-1");
        assertThat(taken).isNotNull();
        assertThat(taken.toolName()).isEqualTo("cancel_order");
        assertThat(taken.fingerprint()).isEqualTo("{\"orderNo\":\"A\"}");
        assertThat(store.take(1L, "mcp-1")).isNull();   // 一次批准对应一次提议，取走即清
    }

    @Test
    void take取走后再拦新的_可正常记录() {
        store.save(1L, "mcp-1", "cancel_order", "{\"orderNo\":\"A\"}");
        store.take(1L, "mcp-1");                        // 批准并清空

        store.save(1L, "mcp-1", "update_order_address", "{\"orderNo\":\"B\"}");
        Pending taken = store.take(1L, "mcp-1");
        assertThat(taken.toolName()).isEqualTo("update_order_address");
        assertThat(taken.fingerprint()).isEqualTo("{\"orderNo\":\"B\"}");
    }

    @Test
    void 不同会话互不影响() {
        store.save(1L, "mcp-1", "cancel_order", "{\"orderNo\":\"A\"}");

        // 另一个会话被拦下的提议照样能记、能取（不撞 mcp-1 的槽位）
        store.save(1L, "s-other", "cancel_order", "{\"orderNo\":\"B\"}");
        Pending taken = store.take(1L, "s-other");
        assertThat(taken.fingerprint()).isEqualTo("{\"orderNo\":\"B\"}");
    }

    @Test
    void 无pending时take返回null() {
        assertThat(store.take(1L, "mcp-1")).isNull();
    }
}
