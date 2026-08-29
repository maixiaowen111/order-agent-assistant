package com.orderagent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 限流：固定时间窗口 + 每窗口计数，超过上限返回 429。
 * key 由调用方拼（如 "query:1" = 用户 1 的 /query），同一 key 在一个窗口内最多放行 limit 次。
 *
 * 窗口从该 key 第一次请求起算（固定窗口，实现最简、够用）；到点自动换新窗口重新计数。
 * 内存实现：单实例部署够用；将来多实例要换成 Redis 计数（INCR + EXPIRE），接口不变。
 */
@Component
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final int limit;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Supplier<Long> clock;

    /** 双构造器必须 @Autowired 指明注入用哪个，否则 Spring 退回无参构造 → 启动崩。 */
    @Autowired
    public RateLimiter(@Value("${agent.rate-limit-per-minute:30}") int limit) {
        this(limit, System::currentTimeMillis);
    }

    /** 测试用：时钟可注入，方便验证窗口滚动。 */
    RateLimiter(int limit, Supplier<Long> clock) {
        this.limit = limit;
        this.clock = clock;
    }

    /** key 在当前窗口内没超限就计数并放行；超限抛 429。 */
    public void tryAcquire(String key) {
        long now = clock.get();
        Window current = windows.compute(key, (k, old) ->
                (old == null || now - old.start >= WINDOW.toMillis())
                        ? new Window(now, 1)      // 新窗口（或旧窗口已过期）
                        : old.bump());            // 同一窗口内 +1
        if (current.count > limit) {
            throw new AgentAuthException(429, "请求太频繁，请稍后再试");
        }
    }

    /** 窗口记录：窗口起点 + 该窗口内已计数。 */
    private static final class Window {
        final long start;
        final int count;

        Window(long start, int count) {
            this.start = start;
            this.count = count;
        }

        Window bump() {
            return new Window(start, count + 1);
        }
    }
}
