package com.orderagent;

import java.util.List;

/**
 * 会话快照：消息历史 + 版本号。
 * 版本号用于乐观并发控制——保存时带上读到的版本，只有"读到的还是现在这个版本"才允许写，
 * 否则说明有别的请求抢先改了会话，直接写会覆盖别人的历史（丢消息）。见 {@link SessionStore#saveIfUnchanged}。
 */
public record SessionSnapshot(List<Message> messages, int version) {
}
