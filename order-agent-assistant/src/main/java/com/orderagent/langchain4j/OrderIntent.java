package com.orderagent.langchain4j;

/**
 * 任务2：模型把用户话转成的结构化意图。
 * 字段和模型约定的 JSON key 一一对应：type / orderNo / address。
 * 模型只负责填这三个值；能不能用，由 Java 侧校验决定（见 IntentRecognizer）。
 */
public record OrderIntent(
        IntentType type,
        String orderNo,
        String address) {

    /** 解析/校验失败时降级到的安全值：没有任何可执行字段 */
    public static final OrderIntent UNKNOWN = new OrderIntent(IntentType.UNKNOWN, null, null);
}
