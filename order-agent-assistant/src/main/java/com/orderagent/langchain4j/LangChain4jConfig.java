package com.orderagent.langchain4j;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 接入点：把 DeepSeek 的 key/base-url/model 喂给框架的模型对象，
 * 替代手写 DeepSeekLlmClient 的模型调用层（任务1先做最小同步 + 流式调用）。
 * 同步版用 OpenAiChatModel（阻塞等完整回答）；流式版用 OpenAiStreamingChatModel（SSE 逐字返回）。
 *
 * 注意这个坑：LangChain4j 的 baseUrl 要根地址（框架自己拼 /chat/completions），
 * 不能复用 DeepSeekLlmClient 用的 deepseek.base-url（那是完整接口地址），
 * 所以这里单独读 deepseek.l4j-base-url。
 */
@Configuration
public class LangChain4jConfig {

    @Bean
    public OpenAiChatModel openAiChatModel(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.l4j-base-url}") String baseUrl,
            @Value("${deepseek.model}") String model) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .build();
    }

    @Bean
    public OpenAiStreamingChatModel openAiStreamingChatModel(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.l4j-base-url}") String baseUrl,
            @Value("${deepseek.model}") String model) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .build();
    }
}
