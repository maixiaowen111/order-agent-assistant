/**
 * 认证管理 — Token 存储、读取、清除
 *
 * 存储位置：localStorage（关浏览器也不丢）
 * 真实生产环境建议 Access Token 放内存、Refresh Token 放 httpOnly Cookie
 */
const Auth = {

    // ========== Token 管理 ==========

    /** 保存登录返回的用户信息和 Token */
    save(user) {
        localStorage.setItem('token', user.token);
        localStorage.setItem('userId', user.id);
        localStorage.setItem('username', user.username);
        localStorage.setItem('role', user.role);
    },

    /** 是否管理员 */
    isAdmin() {
        return localStorage.getItem('role') === 'ADMIN';
    },

    /** 获取 Token */
    getToken() {
        return localStorage.getItem('token');
    },

    /** 获取用户名 */
    getUsername() {
        return localStorage.getItem('username');
    },

    /** 是否已登录 */
    isLogin() {
        const token = this.getToken();
        return token != null && token !== '';
    },

    /** 退出登录 */
    logout() {
        // 通知服务端（将 Token 加入黑名单）
        fetch('/api/user/logout', {
            method: 'POST',
            headers: { 'Authorization': 'Bearer ' + this.getToken() }
        }).catch(() => {});  // 网络错误忽略，不影响前端清理

        localStorage.clear();
        window.location.href = '/login.html';
    },

    /** 获取鉴权 Header */
    getAuthHeader() {
        return { 'Authorization': 'Bearer ' + this.getToken() };
    }
};
