package com.example.order.context;

/**
 * 用户上下文 — 用 ThreadLocal 存储当前请求的用户信息
 *
 * 为什么需要这个类？
 *   Controller → Service → Mapper 调用链很长，每个方法都加 userId 参数太啰嗦。
 *   用 ThreadLocal：拦截器解析 Token 后 set 进来，Service 里直接 get，不用传参。
 *
 * ThreadLocal 是什么？
 *   每个线程有自己独立的存储空间。线程 A set 的值，线程 B 看不到。
 *   HTTP 请求由一个线程从头处理到尾（nio-8080-exec-1 处理请求A，
 *   nio-8080-exec-2 处理请求B），所以 ThreadLocal 天然隔离，不会串数据。
 *
 * 使用方式：
 *   拦截器：UserContext.set(userId, username);
 *   Service：Long userId = UserContext.getUserId();
 *   请求结束：UserContext.clear();  ← 必须清！线程池复用的线程，不清会串数据
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    public static void set(Long userId, String username, String role) {
        USER_ID.set(userId);
        USERNAME.set(username);
        ROLE.set(role);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static String getRole() {
        return ROLE.get();
    }

    /** 是否管理员 */
    public static boolean isAdmin() {
        return "ADMIN".equals(ROLE.get());
    }

    /**
     * 请求结束时必须调用！线程池复用的线程不清 ThreadLocal 会导致数据串到下一个请求
     */
    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
        ROLE.remove();
    }
}
