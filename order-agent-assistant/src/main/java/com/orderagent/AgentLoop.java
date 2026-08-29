package com.orderagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 核心：一圈一圈跑。模型是唯一决策点，harness 只负责执行和搬运。
 * 多轮：按 sessionId 记住每个会话的完整消息历史（存 Redis，见 RedisSessionStore），下次接着聊。
 *
 * 刹车：max-steps。每次模型调用、每次工具执行都算一步，超过上限立即停止，
 * 防止模型"停不下来"把对话拖进无限循环（见 chat 的 step 计数）。
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final LlmClient client;
    private final Map<String, Tool> tools;
    private final PermissionGate gate;
    private final SessionStore store;
    private final int maxSteps;

    /** 兜底构造：测试或没配 agent.max-steps 时用默认 8 步 */
    public AgentLoop(LlmClient client, List<Tool> tools, PermissionGate gate, SessionStore store) {
        this(client, tools, gate, store, 8);
    }

    /** Spring 注入：maxSteps 从 application.yml 的 agent.max-steps 读 */
    @Autowired
    public AgentLoop(LlmClient client, List<Tool> tools, PermissionGate gate, SessionStore store,
                     @Value("${agent.max-steps:8}") int maxSteps) {
        this.client = client;
        this.tools = tools.stream().collect(Collectors.toMap(Tool::name, t -> t));
        this.gate = gate;
        this.store = store;
        this.maxSteps = maxSteps;
    }

    public String chat(String sessionId, Long userId, String userInput) {
        // 从存储取这个会话的历史（没有就新建，自带系统提示词），再追加本轮提问
        List<Message> messages = store.getOrCreate(sessionId);
        messages.add(Message.user(userInput));

        // 一次执行的上下文：sessionId / 提问 / 开始时间 / 步数都装在里面，日志统一取数
        AgentExecutionContext ctx = new AgentExecutionContext(sessionId, userInput);
        log.info("请求开始 sessionId={} query={}", ctx.sessionId(), ctx.userQuery());

        String answer;
        String finalStatus = "SUCCESS";
        boolean budgetExhausted = false; // 工具批量执行到一半耗尽预算 → 直接收尾，不再调模型
        while (true) {
            // 每次模型调用算一步；超过上限不再调模型，直接给用户可理解的提示
            int step = ctx.nextStep();
            if (step > maxSteps) {
                answer = stopMessage();
                finalStatus = "MAX_STEPS";
                log.warn("Agent 停止：步骤超限。sessionId={}, step={}, maxSteps={}, 原因=超过最大执行步数",
                        sessionId, step, maxSteps);
                break;
            }
            LlmResponse resp = client.chat(messages, List.copyOf(tools.values()));
            if (!resp.wantsTools()) {
                answer = resp.text();
                log.info("模型给出最终回答 step={} sessionId={} elapsedMs={} answer={}",
                        step, ctx.sessionId(), ctx.elapsedMs(), LogSanitizer.maskText(answer));
                break;
            }
            messages.add(Message.assistant(resp));
            log.info("模型要调工具 step={} sessionId={} elapsedMs={} tools={}",
                    step, ctx.sessionId(), ctx.elapsedMs(),
                    resp.toolCalls().stream().map(ToolCall::name).toList());
            for (ToolCall call : resp.toolCalls()) {
                // 每个工具执行也算一步；一次返回多个工具时，预算耗尽的就不再执行
                int toolStep = ctx.nextStep();
                if (toolStep > maxSteps) {
                    budgetExhausted = true;
                    messages.add(Message.tool(call.id(), "已达到最大执行步数，已停止执行，请直接给出最终答复。"));
                    log.info("工具未执行 step={} sessionId={} tool={} 原因=步数耗尽",
                            toolStep, ctx.sessionId(), call.name());
                    continue;
                }
                String result = executeTool(call, sessionId, userId);
                messages.add(Message.tool(call.id(), result));
                log.info("工具执行 step={} sessionId={} elapsedMs={} tool={} args={} result={}",
                        toolStep, ctx.sessionId(), ctx.elapsedMs(), call.name(),
                        LogSanitizer.sanitizeArgs(call.args()), LogSanitizer.maskText(result));
            }
            if (budgetExhausted) {
                answer = stopMessage();
                finalStatus = "MAX_STEPS";
                log.warn("Agent 停止：步骤超限（工具批量中耗尽）。sessionId={}, maxSteps={}, 原因=超过最大执行步数",
                        sessionId, maxSteps);
                break;
            }
        }

        // 一次对话结束，把完整历史写回存储，下次接着聊
        store.save(sessionId, messages);
        log.info("请求结束 sessionId={} elapsedMs={} finalStatus={}",
                ctx.sessionId(), ctx.elapsedMs(), finalStatus);
        return answer;
    }

    /** 给用户看的停止提示：不带任何内部细节，只说明发生了啥、下一步怎么办 */
    private String stopMessage() {
        return "抱歉，本次对话的操作步骤已超过上限（" + maxSteps + " 步），为避免无限循环已自动停止。"
                + "您可以换一种说法重新描述需求，或把任务拆小一点再试。";
    }

    /** 人工批准后，把"已批准"这件事喂回给模型，让它的认知跟上闸门状态。
     *  用 user 角色 + 直接命令：模型之前拒绝过"需要人工确认"，这种拒绝是"粘性"的，
     *  被动的 system"你可以执行"压不住它，得让"人"亲自下指令它才会照做。
     *  消息不点名具体工具：可能是取消、也可能是改地址，模型知道自己刚才在做什么，
     *  让它"调用对应的工具"即可覆盖所有写操作。 */
    public void markApproved(String sessionId) {
        List<Message> messages = store.getOrCreate(sessionId);
        messages.add(Message.user("【人工已确认】我已批准你刚才要执行的写操作，请立即调用对应的工具完成它，不要再提示需要人工确认。"));
        store.save(sessionId, messages);
    }

    private String executeTool(ToolCall call, String sessionId, Long userId) {
        if (gate.blocks(call, sessionId, userId)) {
            return gate.reason(call);
        }
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return "未知工具：" + call.name();
        }
        try {
            String result = tool.run(call.args());
            // 写工具批准是一次性的：执行成功后通知闸门消费，防止一次批准被反复复用
            gate.afterToolExecuted(call, sessionId, userId, result);
            return result;
        } catch (Exception e) {
            // 兜底：任何工具异常都不该炸穿整个循环——真异常记给开发看，给模型的是一句干净的话
            // （注意：不打印 args，收货地址属敏感信息）
            log.error("工具执行异常。sessionId={}, tool={}", sessionId, call.name(), e);
            return ToolErrors.fail("TOOL_ERROR", "工具执行失败，请稍后重试");
        }
    }
}
