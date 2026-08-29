package com.orderagent.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务2 验证：不真调 DeepSeek（mock ChatModel 返回固定 JSON），
 * 专测「模型返回的 JSON → OrderIntent → Java 校验」这一串逻辑。
 * 纯离线，空机器能跑绿。
 */
class IntentRecognizerTest {

    private IntentRecognizer newRecognizerReturning(String modelJson) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(modelJson))
                .build());
        return new IntentRecognizer(model);
    }

    @Test
    void parsesCancelOrderIntentWithOrderNo() {
        // 验收样例：模型返回 CANCEL_ORDER + orderNo
        IntentRecognizer recognizer = newRecognizerReturning(
                "{\"type\":\"CANCEL_ORDER\",\"orderNo\":\"20260827001\",\"address\":\"\"}");

        OrderIntent intent = recognizer.recognize("帮我取消订单 20260827001");

        assertThat(intent.type()).isEqualTo(IntentType.CANCEL_ORDER);
        assertThat(intent.orderNo()).isEqualTo("20260827001");
    }

    @Test
    void cancelOrderMissingOrderNoDegradesToUnknown() {
        IntentRecognizer recognizer = newRecognizerReturning(
                "{\"type\":\"CANCEL_ORDER\",\"orderNo\":\"\",\"address\":\"\"}");

        OrderIntent intent = recognizer.recognize("帮我取消一下");

        assertThat(intent.type()).isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    void updateAddressMissingAddressDegradesToUnknown() {
        IntentRecognizer recognizer = newRecognizerReturning(
                "{\"type\":\"UPDATE_ADDRESS\",\"orderNo\":\"20260827001\",\"address\":\"\"}");

        OrderIntent intent = recognizer.recognize("给我改下地址");

        assertThat(intent.type()).isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    void queryOrderNeedsNoRequiredFields() {
        IntentRecognizer recognizer = newRecognizerReturning(
                "{\"type\":\"QUERY_ORDER\",\"orderNo\":\"\",\"address\":\"\"}");

        OrderIntent intent = recognizer.recognize("帮我查下订单");

        assertThat(intent.type()).isEqualTo(IntentType.QUERY_ORDER);
    }

    @Test
    void invalidJsonDegradesToUnknown() {
        IntentRecognizer recognizer = newRecognizerReturning("这不是 JSON");

        OrderIntent intent = recognizer.recognize("随便说点什么");

        assertThat(intent.type()).isEqualTo(IntentType.UNKNOWN);
    }
}
