package com.orderagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PersistedMessage 往返测试。
 * 核心价值：证明"摊平再还原"这条路真的无损——LlmResponse 里被拆开的
 * text 和 toolCalls 能原样拼回去，号牌(toolCallId)不丢。
 */
class PersistedMessageTest {

    @Test
    void 用户消息往返() {
        Message original = Message.user("帮我查一下订单");

        PersistedMessage persisted = PersistedMessage.from(original);
        Message restored = persisted.toMessage();

        assertThat(persisted.role()).isEqualTo("user");
        assertThat(persisted.text()).isEqualTo("帮我查一下订单");
        assertThat(restored.content()).isEqualTo("帮我查一下订单");
        assertThat(restored.toolCallId()).isNull();
    }

    @Test
    void 助手消息的LlmResponse摊平成text和toolCalls再无损还原() {
        ToolCall call = new ToolCall("call_1", "query_order", Map.of("orderNo", "A123"));
        Message original = Message.assistant(new LlmResponse("正在查询", List.of(call)));

        PersistedMessage persisted = PersistedMessage.from(original);
        assertThat(persisted.text()).isEqualTo("正在查询");
        assertThat(persisted.toolCalls()).hasSize(1);

        Message restored = persisted.toMessage();
        assertThat(restored.content()).isInstanceOf(LlmResponse.class);
        LlmResponse resp = (LlmResponse) restored.content();
        assertThat(resp.text()).isEqualTo("正在查询");
        assertThat(resp.toolCalls()).hasSize(1);
        assertThat(resp.toolCalls().get(0).name()).isEqualTo("query_order");
        assertThat(resp.toolCalls().get(0).id()).isEqualTo("call_1");
    }

    @Test
    void 助手消息没有工具调用时还原为空列表() {
        Message original = Message.assistant(new LlmResponse("直接回答", List.of()));

        Message restored = PersistedMessage.from(original).toMessage();

        assertThat(restored.content()).isInstanceOf(LlmResponse.class);
        assertThat(((LlmResponse) restored.content()).toolCalls()).isEmpty();
    }

    @Test
    void 工具消息保留号牌() {
        Message original = Message.tool("call_1", "订单不存在");

        PersistedMessage persisted = PersistedMessage.from(original);
        Message restored = persisted.toMessage();

        assertThat(persisted.toolCallId()).isEqualTo("call_1");
        assertThat(restored.toolCallId()).isEqualTo("call_1");
        assertThat(restored.content()).isEqualTo("订单不存在");
    }
}
