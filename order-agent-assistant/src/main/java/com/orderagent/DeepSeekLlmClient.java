package com.orderagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 真模型：DeepSeek（OpenAI 兼容接口）。
 * 干的活 = 翻译器：Java 对象 ↔ HTTP JSON 的一个往返。
 * key / url / model 都从 application.yml 注入，不写死。
 */
@Component
public class DeepSeekLlmClient implements LlmClient {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    public DeepSeekLlmClient(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.model}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<Tool> tools) {
        try {
            ObjectNode body = buildBody(messages, tools);
            String raw = post(body);
            return parseResponse(raw);
        } catch (Exception e) {
            return new LlmResponse("调用模型失败：" + e.getMessage(), List.of());
        }
    }

    // —— 去程：Java → JSON ——
    private ObjectNode buildBody(List<Message> messages, List<Tool> tools) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        // 低温度：工具选择尽量稳定，别让同一句话这次取消、下次却去查单
        body.put("temperature", 0);
        // 强制最终回答输出 JSON（OpenAI 兼容的 JSON mode）
        ObjectNode fmt = body.putObject("response_format");
        fmt.put("type", "json_object");

        ArrayNode msgs = body.putArray("messages");
        for (Message m : messages) {
            ObjectNode node = msgs.addObject();
            node.put("role", m.role());
            if ("system".equals(m.role()) || "user".equals(m.role())) {
                node.put("content", String.valueOf(m.content()));
            } else if ("assistant".equals(m.role())) {
                LlmResponse resp = (LlmResponse) m.content();
                node.put("content", resp.text() == null ? "" : resp.text());
                if (resp.toolCalls() != null && !resp.toolCalls().isEmpty()) {
                    ArrayNode calls = node.putArray("tool_calls");
                    for (ToolCall c : resp.toolCalls()) {
                        ObjectNode call = calls.addObject();
                        call.put("id", c.id());
                        call.put("type", "function");
                        ObjectNode fn = call.putObject("function");
                        fn.put("name", c.name());
                        fn.put("arguments", toJsonString(c.args()));
                    }
                }
            } else { // "tool"
                node.put("content", String.valueOf(m.content()));
                node.put("tool_call_id", m.toolCallId());
            }
        }

        ArrayNode toolArr = body.putArray("tools");
        for (Tool t : tools) {
            ObjectNode toolNode = toolArr.addObject();
            toolNode.put("type", "function");
            ObjectNode fn = toolNode.putObject("function");
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.set("parameters", json.valueToTree(t.inputSchema()));
        }
        return body;
    }

    // —— 回程：JSON → Java ——
    private LlmResponse parseResponse(String raw) throws Exception {
        JsonNode root = json.readTree(raw);
        JsonNode msg = root.path("choices").get(0).path("message");

        String text = msg.path("content").asText(null);

        List<ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = msg.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                String id = tc.path("id").asText();
                String name = tc.path("function").path("name").asText();
                String argsJson = tc.path("function").path("arguments").asText();
                Map<String, Object> args = json.readValue(argsJson,
                        new TypeReference<Map<String, Object>>() {});
                calls.add(new ToolCall(id, name, args));
            }
        }
        return new LlmResponse(text, calls);
    }

    private String post(ObjectNode body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + "：" + resp.body());
        }
        return resp.body();
    }

    private String toJsonString(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
