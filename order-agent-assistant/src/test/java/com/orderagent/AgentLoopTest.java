package com.orderagent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentLoop 核心循环测试：用"脚本化假模型"代替 DeepSeek，
 * 把模型的行为钉死成预设响应，从而测循环本身对不对——
 * 工具执行、结果喂回、闸门拦截、人工批准注入，全不依赖真实 LLM。
 */
class AgentLoopTest {

    private static final Tool QUERY = new Tool() {
        public String name() { return "query_order"; }
        public String description() { return "查询订单"; }
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        public String run(Map<String, Object> args) { return "订单状态：PAID"; }
    };

    private static final Tool CANCEL = new Tool() {
        public String name() { return "cancel_order"; }
        public String description() { return "取消订单"; }
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        public String run(Map<String, Object> args) { return "订单已取消"; }
        public boolean readOnly() { return false; }
    };

    /** 内存版存储：测试用，顺便验证 AgentLoop 真的存取了会话。 */
    private static class InMemoryStore implements SessionStore {
        final Map<String, List<Message>> data = new ConcurrentHashMap<>();

        public List<Message> getOrCreate(String sessionId) {
            return data.computeIfAbsent(sessionId,
                    id -> new ArrayList<>(List.of(Message.system("你是测试助手"))));
        }

        public void save(String sessionId, List<Message> messages) {
            data.put(sessionId, new ArrayList<>(messages));
        }
    }

    /** 脚本化假模型：按队列依次吐出预设响应，并记录每轮收到的消息供断言。 */
    private static class ScriptedLlm implements LlmClient {
        final List<LlmResponse> script;
        final List<List<Message>> received = new ArrayList<>();

        ScriptedLlm(List<LlmResponse> script) {
            this.script = new ArrayList<>(script);
        }

        public LlmResponse chat(List<Message> messages, List<Tool> tools) {
            received.add(List.copyOf(messages));
            return script.remove(0);
        }
    }

    private static class AlwaysAllowGate implements PermissionGate {
        public boolean blocks(ToolCall call, String sessionId) { return false; }
    }

    @Test
    void 模型直接回答时_一轮就结束() {
        ScriptedLlm llm = new ScriptedLlm(List.of(new LlmResponse("查单结果：PAID", List.of())));
        AgentLoop loop = new AgentLoop(llm, List.of(QUERY), new AlwaysAllowGate(), new InMemoryStore());

        String answer = loop.chat("s1", "查一下订单");

        assertThat(answer).isEqualTo("查单结果：PAID");
        assertThat(llm.received).hasSize(1);
        assertThat(llm.received.get(0)).anyMatch(m -> "user".equals(m.role()));
    }

    @Test
    void 模型要调工具时_执行并把结果喂回_再拿到最终回答() {
        ToolCall call = new ToolCall("call_1", "query_order", Map.of("orderNo", "A123"));
        ScriptedLlm llm = new ScriptedLlm(List.of(
                new LlmResponse(null, List.of(call)),
                new LlmResponse("订单状态：PAID", List.of())
        ));
        InMemoryStore store = new InMemoryStore();
        AgentLoop loop = new AgentLoop(llm, List.of(QUERY), new AlwaysAllowGate(), store);

        String answer = loop.chat("s1", "查一下订单 A123");

        assertThat(answer).isEqualTo("订单状态：PAID");
        // 第二轮发给模型的消息里，带上了工具结果：role=tool、号牌对应、内容是工具返回值
        List<Message> round2 = llm.received.get(1);
        assertThat(round2).anyMatch(m -> "tool".equals(m.role())
                && "call_1".equals(m.toolCallId())
                && "订单状态：PAID".equals(m.content()));
        // 历史已持久化到存储
        assertThat(store.data.get("s1")).anyMatch(m -> "tool".equals(m.role()));
    }

    @Test
    void 写工具被闸门拦截时_模型收到的是拦截原因_而非工具结果() {
        ToolCall call = new ToolCall("call_1", "cancel_order", Map.of("orderNo", "A123"));
        ScriptedLlm llm = new ScriptedLlm(List.of(
                new LlmResponse(null, List.of(call)),
                new LlmResponse("需要您确认后取消", List.of())
        ));
        PermissionGate blockingGate = new PermissionGate() {
            public boolean blocks(ToolCall c, String s) { return true; }
            public String reason(ToolCall c) { return "写操作被拦截：取消订单 A123 需要人工确认。"; }
        };
        InMemoryStore store = new InMemoryStore();
        AgentLoop loop = new AgentLoop(llm, List.of(CANCEL), blockingGate, store);

        String answer = loop.chat("s1", "取消订单 A123");

        assertThat(answer).isEqualTo("需要您确认后取消");
        List<Message> round2 = llm.received.get(1);
        // 模型收到的是拦截原因，不是真正执行结果
        assertThat(round2).anyMatch(m -> "tool".equals(m.role())
                && m.content().toString().contains("需要人工确认"));
        assertThat(round2).noneMatch(m -> "订单已取消".equals(m.content()));
    }

    @Test
    void 工具执行抛异常时_循环不崩_把干净错误喂回模型() {
        ToolCall call = new ToolCall("call_1", "query_order", Map.of("orderNo", "A123"));
        Tool crashTool = new Tool() {
            public String name() { return "query_order"; }
            public String description() { return "查询订单"; }
            public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            public String run(Map<String, Object> args) { throw new IllegalStateException("数据库连接池耗尽"); }
        };
        ScriptedLlm llm = new ScriptedLlm(List.of(
                new LlmResponse(null, List.of(call)),
                new LlmResponse("查询失败，请稍后重试", List.of())
        ));
        AgentLoop loop = new AgentLoop(llm, List.of(crashTool), new AlwaysAllowGate(), new InMemoryStore());

        String answer = loop.chat("s1", "查一下订单 A123");

        // 循环没炸，模型收到的是干净的结构化错误（不含堆栈），并能正常收尾
        assertThat(answer).isEqualTo("查询失败，请稍后重试");
        List<Message> round2 = llm.received.get(1);
        assertThat(round2).anyMatch(m -> "tool".equals(m.role())
                && m.content().toString().contains("\"success\":false")
                && !m.content().toString().contains("数据库连接池耗尽"));
    }

    @Test
    void markApproved把人工确认消息注入会话() {
        ScriptedLlm llm = new ScriptedLlm(List.of(new LlmResponse("收到", List.of())));
        InMemoryStore store = new InMemoryStore();
        AgentLoop loop = new AgentLoop(llm, List.of(QUERY), new AlwaysAllowGate(), store);

        loop.markApproved("s1");

        List<Message> saved = store.data.get("s1");
        assertThat(saved).anyMatch(m -> "user".equals(m.role())
                && m.content().toString().contains("人工已确认"));
    }

    /**
     * 无限循环测试：模型被 Mockito 钉死成"永远返回同一个工具调用"。
     * 步数预算 8，每轮 = 模型调用(1) + 工具执行(1) = 2 步 → 4 轮后预算耗尽，
     * 第 5 轮该调模型时 step 已到 9 > 8，直接给用户停止提示，循环安全结束。
     */
    @Test
    void 模型持续返回工具调用时_超过最大步数安全停止() {
        ToolCall call = new ToolCall("call_1", "query_order", Map.of("orderNo", "A123"));
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList(), anyList())).thenReturn(new LlmResponse(null, List.of(call)));

        AtomicInteger toolRuns = new AtomicInteger();
        Tool countingQuery = new Tool() {
            public String name() { return "query_order"; }
            public String description() { return "查询订单"; }
            public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            public String run(Map<String, Object> args) { toolRuns.incrementAndGet(); return "订单状态：PAID"; }
        };

        AgentLoop loop = new AgentLoop(llm, List.of(countingQuery), new AlwaysAllowGate(), new InMemoryStore(), 8);

        String answer = loop.chat("s1", "查一下订单");

        // 没有死循环，返回了用户能看懂的停止提示
        assertThat(answer).contains("自动停止");
        // 预算 8 步被精确用完：模型 4 次 + 工具 4 次
        assertThat(toolRuns.get()).isEqualTo(4);
        verify(llm, times(4)).chat(anyList(), anyList());
    }

    /**
     * 批量截断测试：模型一次返回 3 个工具，步数预算只有 3 步。
     * 账：第 1 步=模型调用，第 2、3 步=前两个工具执行；轮到第 3 个工具时
     * step 已到 4 > 3 → 预算中途耗尽，它被跳过（不执行、不调模型），
     * 直接给用户停止提示收尾。对应"一次返回多个工具、预算中途耗尽"的边界，
     * 此前没有任何测试覆盖这条截断逻辑。
     */
    @Test
    void 模型一次返回多个工具_预算中途耗尽_超出的工具被跳过且不再调模型() {
        ToolCall a = new ToolCall("call_a", "query_order", Map.of("orderNo", "A1"));
        ToolCall b = new ToolCall("call_b", "query_order", Map.of("orderNo", "B1"));
        ToolCall c = new ToolCall("call_c", "query_order", Map.of("orderNo", "C1"));
        ScriptedLlm llm = new ScriptedLlm(List.of(new LlmResponse(null, List.of(a, b, c))));

        AtomicInteger toolRuns = new AtomicInteger();
        Tool countingQuery = new Tool() {
            public String name() { return "query_order"; }
            public String description() { return "查询订单"; }
            public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            public String run(Map<String, Object> args) { toolRuns.incrementAndGet(); return "订单状态：PAID"; }
        };
        InMemoryStore store = new InMemoryStore();
        AgentLoop loop = new AgentLoop(llm, List.of(countingQuery), new AlwaysAllowGate(), store, 3);

        String answer = loop.chat("s1", "帮我查三个订单");

        // 只有前两个工具真执行了，第 3 个预算耗尽被跳过
        assertThat(toolRuns.get()).isEqualTo(2);
        // 停在给用户的提示上，没有进入下一轮模型调用
        assertThat(answer).contains("自动停止");
        assertThat(llm.received).hasSize(1);
        // 被跳过的那个工具，模型收到的是"步数耗尽"说明，而不是真实执行结果
        List<Message> saved = store.data.get("s1");
        assertThat(saved).anyMatch(m -> "tool".equals(m.role())
                && "call_c".equals(m.toolCallId())
                && m.content().toString().contains("已达到最大执行步数"));
    }
}
