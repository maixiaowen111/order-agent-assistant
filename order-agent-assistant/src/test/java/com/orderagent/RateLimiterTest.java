package com.orderagent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 限流器测试：固定 60 秒窗口，同一 key 窗口内最多放行 limit 次，超限 429。
 * 时钟用可注入版本（Supplier<Long>），不 sleep、不用真实时间，任意机器上都能绿。
 */
class RateLimiterTest {

    @Test
    void 窗口内未超限_放行() {
        RateLimiter rl = new RateLimiter(2, () -> 1000L);

        rl.tryAcquire("query:1"); // 第 1 次
        rl.tryAcquire("query:1"); // 第 2 次（恰好到上限，放行）
    }

    @Test
    void 超过窗口上限_429() {
        RateLimiter rl = new RateLimiter(2, () -> 1000L);
        rl.tryAcquire("query:1");
        rl.tryAcquire("query:1");

        assertThatThrownBy(() -> rl.tryAcquire("query:1")) // 第 3 次 → 429
                .isInstanceOfSatisfying(AgentAuthException.class, e -> assertThat(e.status()).isEqualTo(429));
    }

    @Test
    void 窗口滚动后_重新计数() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter rl = new RateLimiter(2, clock::get);
        rl.tryAcquire("query:1");
        rl.tryAcquire("query:1");
        assertThatThrownBy(() -> rl.tryAcquire("query:1"))
                .isInstanceOfSatisfying(AgentAuthException.class, e -> assertThat(e.status()).isEqualTo(429));

        clock.set(60_000); // 60 秒后：旧窗口过期，换新窗口从 0 数
        rl.tryAcquire("query:1");
        rl.tryAcquire("query:1");
        assertThatThrownBy(() -> rl.tryAcquire("query:1"))
                .isInstanceOfSatisfying(AgentAuthException.class, e -> assertThat(e.status()).isEqualTo(429));
    }

    @Test
    void 不同key各自独立计数() {
        RateLimiter rl = new RateLimiter(1, () -> 1000L);
        rl.tryAcquire("query:1");

        assertThatThrownBy(() -> rl.tryAcquire("query:1"))
                .isInstanceOfSatisfying(AgentAuthException.class, e -> assertThat(e.status()).isEqualTo(429));

        rl.tryAcquire("query:2"); // 别的用户/别的接口不受影响
    }
}
