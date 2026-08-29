package com.orderagent;

/**
 * 当前请求登录用户的 ThreadLocal 载体。
 * AgentAuthInterceptor 从 Authorization: Bearer <token> 解析出 userId 放进这里，
 * 同线程的 Controller / 闸门 / OrderSystemApiClient 直接取，请求结束在 afterCompletion 清掉。
 *
 * 作用：/query、/approve 从此绑定"是哪个用户在操作"，sessionId 不再是无主的——
 * 之前只认 sessionId、不认人，猜到/伪造一个 sessionId 就能冒用别人的会话和批准。
 */
public final class AgentUserContext {

    private static final ThreadLocal<Long> USER = new ThreadLocal<>();

    private AgentUserContext() {
    }

    public static void set(Long userId) {
        USER.set(userId);
    }

    public static Long get() {
        return USER.get();
    }

    public static void clear() {
        USER.remove();
    }
}
