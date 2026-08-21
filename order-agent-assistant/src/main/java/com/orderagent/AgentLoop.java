package com.orderagent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 核心：一圈一圈跑。模型是唯一决策点，harness 只负责执行和搬运。
 * 多轮：按 sessionId 记住每个会话的完整消息历史（存 Redis，见 RedisSessionStore），下次接着聊。
 */
@Component
public class AgentLoop {

    private final LlmClient client;
    private final Map<String, Tool> tools;
    private final PermissionGate gate;
    private final SessionStore store;

    public AgentLoop(LlmClient client, List<Tool> tools, PermissionGate gate, SessionStore store) {
        this.client = client;
        this.tools = tools.stream().collect(Collectors.toMap(Tool::name, t -> t));
        this.gate = gate;
        this.store = store;
    }

    public String chat(String sessionId, String userInput) {
        // 从存储取这个会话的历史（没有就新建，自带系统提示词），再追加本轮提问
        List<Message> messages = store.getOrCreate(sessionId);
        messages.add(Message.user(userInput));

        String answer;
        while (true) {
            LlmResponse resp = client.chat(messages, List.copyOf(tools.values()));
            if (!resp.wantsTools()) {
                answer = resp.text();
                break;
            }
            messages.add(Message.assistant(resp));
            for (ToolCall call : resp.toolCalls()) {
                messages.add(Message.tool(call.id(), executeTool(call, sessionId)));
            }
        }

        // 一次对话结束，把完整历史写回存储，下次接着聊
        store.save(sessionId, messages);
        return answer;
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

    private String executeTool(ToolCall call, String sessionId) {
        if (gate.blocks(call, sessionId)) {
            return gate.reason(call);
        }
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return "未知工具：" + call.name();
        }
        return tool.run(call.args());
    }
}
