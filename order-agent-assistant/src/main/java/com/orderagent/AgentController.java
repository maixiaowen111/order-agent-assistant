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
 *
 * 本层的防御（除了登录，还有）：
 *   ① 请求体用 DTO，q 必填、有最大长度，sessionId 有长度/字符集校验（防超长/怪异 key）；
 *   ② 限流：每用户每分钟 /query、/approve 各限次数，超限 429（见 RateLimiter）；
 *   ③ 请求体大小上限：/query、/approve 超过 64KB 直接 413（见 MaxBodySizeFilter）；
 *   ④ 会话归属绑定用 SETNX 原子完成：两个并发请求抢同一个新会话，只有一个能赢（见 resolveSession）。
 */
@RestController
public class AgentController {

    private static final int MAX_Q_LEN = 2000;
    private static final int MAX_SESSION_LEN = 64;
    /** sessionId 只允许 URL 安全字符，防止把诡异字符塞进 Redis key 造出异常键 */
    private static final String SESSION_PATTERN = "[A-Za-z0-9_-]+";

    private final AgentLoop loop;
    private final WritePermissionGate gate;
    private final ToolProposalGate proposalGate;
    private final SessionStore store;
    private final RateLimiter rateLimiter;
    private final ObjectMapper json = new ObjectMapper();

    public AgentController(AgentLoop loop, WritePermissionGate gate,
                           ToolProposalGate proposalGate, SessionStore store, RateLimiter rateLimiter) {
        this.loop = loop;
        this.gate = gate;
        this.proposalGate = proposalGate;
        this.store = store;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody AgentQueryRequest req) {
        Long userId = requireLogin();
        String q = validateQuery(req.q());                  // q 必填、有长度上限
        String sid = normalizeSessionId(req.sessionId());   // sessionId 长度/格式校验，空则新建
        rateLimiter.tryAcquire("query:" + userId);          // 限流：超限 429
        String ownerChecked = resolveSession(sid, userId);  // 会话归属：自己的才放行

        String raw = loop.chat(ownerChecked, userId, q);

        // 模型吐的是 JSON 字符串，这里解析成结构化字段返回给调用方
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", ownerChecked);
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
    public Map<String, String> approve(@RequestBody AgentApproveRequest req) {
        Long userId = requireLogin();
        String sid = normalizeSessionId(req.sessionId());
        rateLimiter.tryAcquire("approve:" + userId);

        // 只能批准自己名下的会话：防止 A 冒用 B 的 sessionId 给 B 的写操作"点头"
        Long owner = store.ownerOf(sid);
        if (owner == null || !owner.equals(userId)) {
            throw new AgentAuthException(403, "无权访问该会话");
        }

        gate.approve(userId, sid);                          // 手写路径：放行"该会话最后被拦的那次写调用"
        proposalGate.approveLastBlocked(userId, sid);       // LangChain4j 路径：放行"最后被拦的那次提议"（工具+参数指纹级）
        loop.markApproved(sid);                             // 手写路径：喂给模型"已批准"（认知）
        return Map.of("result", "已批准该会话的写操作，现在重新让 agent 执行即可");
    }

    private Long requireLogin() {
        Long userId = AgentUserContext.get();
        if (userId == null) {
            throw new AgentAuthException(401, "未登录");
        }
        return userId;
    }

    /** q 校验：必填、最大长度。 */
    private String validateQuery(String q) {
        if (q == null || q.isBlank()) {
            throw new AgentAuthException(400, "q 不能为空");
        }
        if (q.length() > MAX_Q_LEN) {
            throw new AgentAuthException(400, "q 过长（最多 " + MAX_Q_LEN + " 字）");
        }
        return q;
    }

    /** sessionId 归一化：空 → 新建随机；有值 → 校验长度与字符集。 */
    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        if (sessionId.length() > MAX_SESSION_LEN) {
            throw new AgentAuthException(400, "sessionId 过长（最多 " + MAX_SESSION_LEN + " 字符）");
        }
        if (!sessionId.matches(SESSION_PATTERN)) {
            throw new AgentAuthException(400, "sessionId 格式不合法");
        }
        return sessionId;
    }

    /** 会话归属：已有会话必须是自己的；没有则用 SETNX 原子绑定到当前用户。 */
    private String resolveSession(String sid, Long userId) {
        Long owner = store.ownerOf(sid);
        if (owner != null && !owner.equals(userId)) {
            throw new AgentAuthException(403, "无权访问该会话");
        }
        if (owner == null && !store.bindOwnerIfAbsent(sid, userId)) {
            // 抢新会话抢输了：同一瞬间有别人先绑定了它 → 会话已归他人，不能碰
            throw new AgentAuthException(403, "无权访问该会话");
        }
        return sid;
    }
}
