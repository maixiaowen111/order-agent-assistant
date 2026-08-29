package com.orderagent.langchain4j;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务1第2步的验证：抓同一个坑——LangChain4j 的 baseUrl 必须传根地址，
 * 不能把手写 DeepSeekLlmClient 用的完整接口地址传给它。
 * 纯本地、不发网络请求、不启 Spring 上下文，空机器能跑绿。
 */
class LangChain4jConfigTest {

    @Test
    void langChain4jBaseUrlIsRootNotFullEndpoint() {
        Map<String, Object> deepseek = readDeepseekConfig();
        String l4jBaseUrl = (String) deepseek.get("l4j-base-url");
        String handWrittenBaseUrl = (String) deepseek.get("base-url");

        // 两条 URL 只差 /chat/completions 这一段——正是 LangChain4j 会自动拼的那段
        assertThat(l4jBaseUrl).isEqualTo("https://api.deepseek.com");
        assertThat(handWrittenBaseUrl).isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(handWrittenBaseUrl).endsWith("/chat/completions");
    }

    @Test
    void configBuildsNonNullModel() {
        OpenAiChatModel model = new LangChain4jConfig()
                .openAiChatModel("dummy-key", "https://api.deepseek.com", "deepseek-chat");
        assertThat(model).isNotNull();
    }

    @Test
    void streamingConfigBuildsNonNullModel() {
        OpenAiStreamingChatModel model = new LangChain4jConfig()
                .openAiStreamingChatModel("dummy-key", "https://api.deepseek.com", "deepseek-chat");
        assertThat(model).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDeepseekConfig() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            return (Map<String, Object>) root.get("deepseek");
        } catch (Exception e) {
            throw new IllegalStateException("读不到 application.yml", e);
        }
    }
}
