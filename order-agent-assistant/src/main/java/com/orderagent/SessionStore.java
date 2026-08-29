package com.orderagent;

import java.util.List;

/**
 * 会话存储抽象：agent 的"记忆"放哪。
 * 现在有 Redis 实现（跨请求、跨重启、多实例共享）；将来可换数据库/Memcached 等。
 * AgentLoop 只依赖这个接口，不关心具体存储。
 *
 * 归属（owner）：每个会话记录创建它的用户。/query 和 /approve 先校验归属，
 * 防止用户伪造 sessionId 冒用别人的会话（会话和批准都按用户隔离）。
 */
public interface SessionStore {

    /** 取某会话的历史消息；没有就新建（带上系统提示词）。 */
    List<Message> getOrCreate(String sessionId);

    /** 把一次对话后的完整历史写回去。 */
    void save(String sessionId, List<Message> messages);

    /** 绑定会话归属用户；只对新建会话调用一次。 */
    void bindOwner(String sessionId, Long userId);

    /** 查询会话归属用户；会话不存在返回 null。 */
    Long ownerOf(String sessionId);
}
