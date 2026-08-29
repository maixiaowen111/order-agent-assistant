package com.orderagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderagent.langchain4j.ToolProposalGate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST 入口：把 agent 变成一个能调用的服务。
 * POST /query   问 agent（多轮），返回结构化 JSON —— 登录用户才能调用
 * POST /approve 人工批准一个写操作 —— 只能批准自己名下的会话
 *
 * 两个接口都绑定登录用户：AgentAuthInterceptor 从 Authorization: Bearer <token>
 * 解析出 userId 放进 AgentUserContext，这里取出来校验会话归属、绑定批准。
 * sessionId 不再是无主的——伪造/冒用别人的 sessionId 会被 403 拒绝。
 */
@RestController
public class AgentController {

    private final AgentLoop loop;
    private final WritePermissionGate gate;
    private final ToolProposalGate proposalGate;
    private final SessionStore store;
    private final ObjectMapper json = new ObjectMapper();

    public AgentController(AgentLoop loop, WritePermissionGate gate,
                           ToolProposalGate proposalGate, SessionStore store) {
        this.loop = loop;
        this.gate = gate;
        this.proposalGate = proposalGate;
        this.store = store;
    }

    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody Map<String, String> body) {
        Long userId = requireLogin();
        String q = body.get("q");
        String sessionId = body.get("sessionId");
        // 会话归属：已有会话必须是自己的；没有则新建并绑定到当前用户
        String sid = resolveSession(sessionId, userId);

        String raw = loop.chat(sid, userId, q);

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
    public Map<String, String> approve(@RequestBody Map<String, String> body) {
        Long userId = requireLogin();
        String sessionId = body.get("sessionId");

        // 只能批准自己名下的会话：防止 A 冒用 B 的 sessionId 给 B 的写操作"点头"
        Long owner = store.ownerOf(sessionId);
        if (owner == null || !owner.equals(userId)) {
            throw new AgentAuthException(403, "无权访问该会话");
        }

        gate.approve(userId, sessionId);                    // 手写路径：放行"该会话最后被拦的那次写调用"
        proposalGate.approveLastBlocked(userId, sessionId); // LangChain4j 路径：放行"最后被拦的那次提议"（工具+参数指纹级）
        loop.markApproved(sessionId);                       // 手写路径：喂给模型"已批准"（认知）
        return Map.of("result", "已批准该会话的写操作，现在重新让 agent 执行即可");
    }

    private Long requireLogin() {
        Long userId = AgentUserContext.get();
        if (userId == null) {
            throw new AgentAuthException(401, "未登录");
        }
        return userId;
    }

    private String resolveSession(String sessionId, Long userId) {
        String sid = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId;
        Long owner = store.ownerOf(sid);
        if (owner != null && !owner.equals(userId)) {
            throw new AgentAuthException(403, "无权访问该会话");
        }
        if (owner == null) {
            store.bindOwner(sid, userId);
        }
        return sid;
    }
}
