package com.orderagent.langchain4j;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 任务1第2/3步：LangChain4j 对话 Demo——证明框架能替 DeepSeekLlmClient 跟 DeepSeek 说上话。
 * 同步（ask）与流式（askStreaming）两种模式都在这。
 * 不自动执行（启动时不会调模型）；要真跑需要 DEEPSEEK_API_KEY + 网络。
 */
@Component
public class LangChain4jChatDemo {

    private final OpenAiChatModel model;
    private final OpenAiStreamingChatModel streamingModel;

    public LangChain4jChatDemo(OpenAiChatModel model, OpenAiStreamingChatModel streamingModel) {
        this.model = model;
        this.streamingModel = streamingModel;
    }

    /** 同步问一句话，阻塞等完整回答（ChatModel.chat(String) 直接返回文字） */
    public String ask(String userMessage) {
        return model.chat(userMessage);
    }

    /**
     * 流式问一句话：不阻塞等完整回答，模型每吐一个字就回调一次 onToken。
     * 流程：用户话 → 请求发出 → onToken 逐字收 → onComplete 收尾 → 出错走 onError。
     */
    public void askStreaming(String userMessage, Consumer<String> onToken, Consumer<Throwable> onError) {
        streamingModel.chat(userMessage, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                onToken.accept(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                // 流式结束；response.content() 是拼好的完整回答（本例不需要，什么都不做）
            }

            @Override
            public void onError(Throwable error) {
                onError.accept(error);
            }
        });
    }
}
