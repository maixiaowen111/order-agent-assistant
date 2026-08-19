package com.orderagent;

import java.util.List;

/**
 * 会话存储抽象：agent 的"记忆"放哪。
 * 现在有 Redis 实现（跨请求、跨重启、多实例共享）；将来可换数据库/Memcached 等。
 * AgentLoop 只依赖这个接口，不关心具体存储。
 */
public interface SessionStore {

    /** 取某会话的历史消息；没有就新建（带上系统提示词）。 */
    List<Message> getOrCreate(String sessionId);

    /** 把一次对话后的完整历史写回去。 */
    void save(String sessionId, List<Message> messages);
}
