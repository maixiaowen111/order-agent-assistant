package com.orderagent;

import java.util.List;

/**
 * 跟模型对话的通道：把「消息 + 工具」发过去，拿回文字或工具调用。
 * 现在只有假实现 FakeLlmClient；真实现是"Java 对象 ↔ HTTP JSON 的一个往返"。
 */
public interface LlmClient {

    LlmResponse chat(List<Message> messages, List<Tool> tools);
}
