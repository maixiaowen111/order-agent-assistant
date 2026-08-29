package com.orderagent.langchain4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 任务2：结构化意图识别。
 * 分工：模型只负责"把用户话翻译成结构化 JSON"；能不能用、字段齐不齐，由 Java 侧校验。
 * 模型永远不直接执行任何操作——这是和写工具（任务4）+ 权限闸门配套的安全前提。
 */
@Component
public class IntentRecognizer {

    private static final String PROMPT = """
            你是一个订单助手意图识别器。根据用户的话判断意图，只输出 JSON，不要任何其他文字。
            JSON 只有三个字段：
            {"type": "QUERY_ORDER|QUERY_STOCK|CANCEL_ORDER|UPDATE_ADDRESS|UNKNOWN", "orderNo": "订单号或空字符串", "address": "地址或空字符串"}
            规则：
            - 查订单/查库存：QUERY_ORDER / QUERY_STOCK，orderNo、address 可为空
            - 取消订单：CANCEL_ORDER，orderNo 必填
            - 改收货地址：UPDATE_ADDRESS，orderNo 和 address 都必填
            - 没看懂或不在上述范围：UNKNOWN
            """;

    private final ChatModel model;
    private final ObjectMapper json = new ObjectMapper();

    public IntentRecognizer(ChatModel model) {
        this.model = model;
    }

    /** 把用户话解析成意图对象；任何一步失败都降级成 UNKNOWN，绝不抛出业务操作 */
    public OrderIntent recognize(String userMessage) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from(PROMPT + "\n用户说：" + userMessage)))
                .responseFormat(ResponseFormat.JSON)  // 和 DeepSeekLlmClient 一样的 JSON 模式
                .build();
        String raw = model.chat(request).aiMessage().text();
        return parseAndValidate(raw);
    }

    /** Java 侧校验：JSON 字段对不上 / 必填缺失 → 降级 UNKNOWN（模型不执行任何操作） */
    private OrderIntent parseAndValidate(String raw) {
        try {
            OrderIntent intent = json.readValue(raw, OrderIntent.class);
            IntentType type = intent.type() == null ? IntentType.UNKNOWN : intent.type();
            return switch (type) {
                // 只读：不需要额外字段
                case QUERY_ORDER, QUERY_STOCK -> new OrderIntent(type, intent.orderNo(), intent.address());
                // 取消订单：订单号必填
                case CANCEL_ORDER -> hasOrderNo(intent)
                        ? new OrderIntent(type, intent.orderNo(), null)
                        : OrderIntent.UNKNOWN;
                // 改地址：订单号 + 地址都必填
                case UPDATE_ADDRESS -> hasOrderNo(intent) && notBlank(intent.address())
                        ? new OrderIntent(type, intent.orderNo(), intent.address())
                        : OrderIntent.UNKNOWN;
                default -> OrderIntent.UNKNOWN;
            };
        } catch (Exception e) {
            // JSON 解析失败 / 枚举对不上 / 缺字段 → 一律 UNKNOWN
            return OrderIntent.UNKNOWN;
        }
    }

    private boolean hasOrderNo(OrderIntent intent) {
        return notBlank(intent.orderNo());
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
