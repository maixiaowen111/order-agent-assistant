package com.orderagent;

import java.util.List;

/**
 * 会话存储抽象：agent 的"记忆"放哪。
 * 现在有 Redis 实现（跨请求、跨重启、多实例共享）；将来可换数据库/Memcached 等。
 * AgentLoop 只依赖这个接口，不关心具体存储。
 *
 * 两个并发保证（这是会话安全的核心，别在实现里退化成裸 set）：
 *   ① 归属绑定用 SETNX（{@link #bindOwnerIfAbsent}）原子完成——
 *      两个请求同时抢同一个新会话，只有一个能赢，输的拿不到（说明会话已归别人，别碰）。
 *   ② 保存带版本号（{@link #saveIfUnchanged}）做乐观锁——
 *      两个请求同时读同一个会话、各自追加消息后先后保存，第二个会因版本不符保存失败，
 *      从而不覆盖第一个写下的历史（丢消息）。失败方返回提示让用户重试即可。
 *
 * 归属（owner）：每个会话记录创建它的用户。/query 和 /approve 先校验归属，
 * 防止用户伪造 sessionId 冒用别人的会话（会话和批准都按用户隔离）。
 */
public interface SessionStore {

    /** 取某会话的快照（历史 + 版本号）；没有就新建（带上系统提示词，版本 0）。 */
    SessionSnapshot getOrCreate(String sessionId);

    /**
     * 带版本校验地保存：仅当当前版本仍等于 expectedVersion 才写入并 +1，返回 true。
     * 返回 false = 有并发请求已抢先更新过 → 本次不写，避免覆盖对方历史。
     */
    boolean saveIfUnchanged(String sessionId, List<Message> messages, int expectedVersion);

    /** 原子绑定会话归属用户：仅当该会话还没有归属才绑定，成功返回 true。只对新建会话调用。 */
    boolean bindOwnerIfAbsent(String sessionId, Long userId);

    /** 查询会话归属用户；会话不存在返回 null。 */
    Long ownerOf(String sessionId);
}
