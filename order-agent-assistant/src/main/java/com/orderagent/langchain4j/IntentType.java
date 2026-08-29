package com.orderagent.langchain4j;

/** 任务2：用户意图的类型。模型把话翻译成这些枚举之一，Java 侧再校验。 */
public enum IntentType {
    QUERY_ORDER,      // 查订单（只读）
    QUERY_STOCK,      // 查库存（只读）
    CANCEL_ORDER,     // 取消订单（写，需权限闸门批准）
    UPDATE_ADDRESS,   // 改收货地址（写，需权限闸门批准）
    UNKNOWN           // 解析不出 / 不在范围
}
