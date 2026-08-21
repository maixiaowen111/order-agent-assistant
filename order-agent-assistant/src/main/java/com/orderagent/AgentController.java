package com.orderagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST 入口：把 agent 变成一个能调用的服务。
 * GET  /query?q=问题[&sessionId=会话id]   问 agent（多轮），返回结构化 JSON
 * POST /approve?sessionId=..   人工批准一个写操作
 */
@RestController
public class AgentController {

    private final AgentLoop loop;
    private final WritePermissionGate gate;
    private final ObjectMapper json = new ObjectMapper();

    public AgentController(AgentLoop loop, WritePermissionGate gate) {
        this.loop = loop;
        this.gate = gate;
    }

    @GetMapping("/query")
    public Map<String, Object> query(@RequestParam("q") String q,
                                     @RequestParam(value = "sessionId", required = false) String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId;
        String raw = loop.chat(sid, q);

        // 模型吐的是 JSON 字符串，这里解析成结构化字段返回给调用方
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sid);
        try {
            JsonNode node = json.readTree(raw);
            if (node.isObject()) {
                node.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
            } else {
                result.put("answer", raw);
            }
        } catch (Exception e) {
            result.put("answer", raw); // 兜底：模型没吐 JSON 就原样返回
        }
        return result;
    }

    @PostMapping("/approve")
    public Map<String, String> approve(@RequestParam("sessionId") String sessionId) {
        gate.approve(sessionId);      // 打开闸门（状态）
        loop.markApproved(sessionId); // 告诉模型（认知）
        return Map.of("result", "已批准该会话的写操作，现在重新让 agent 执行即可");
    }
}
