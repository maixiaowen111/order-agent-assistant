/**
 * API 请求封装
 *
 * 所有接口请求的统一入口。职责：
 *   ① 自动带 Authorization Header
 *   ② 统一处理 401（Token 过期 → 跳登录）
 *   ③ 统一解析 JSON 响应
 *   ④ 返回 Result.data，调用方不需要 .then(res => res.json())
 */
const API = {

    BASE: '',  // 同域请求，不需要写完整 URL

    /**
     * 通用请求
     *
     * @param {string} url    接口路径，如 /api/product/page
     * @param {object} options fetch 的第二个参数（method, body 等）
     * @param {boolean} auth  是否需要鉴权（默认 true）
     * @returns {Promise}     接口返回的 data 字段
     */
    async request(url, options = {}, auth = true) {
        const headers = { 'Content-Type': 'application/json' };

        if (auth) {
            headers['Authorization'] = 'Bearer ' + Auth.getToken();
        }

        const response = await fetch(this.BASE + url, {
            ...options,
            headers: { ...headers, ...options.headers }
        });

        const result = await response.json();

        // 鉴权失败 → 清缓存 → 跳登录页
        if (result.code === 401) {
            localStorage.clear();
            if (window.location.pathname !== '/login.html') {
                window.location.href = '/login.html';
            }
            throw new Error(result.message);
        }

        // 业务失败
        if (result.code !== 200) {
            throw new Error(result.message);
        }

        return result.data;
    },

    // ========== GET ==========
    get(url, auth = true) {
        return this.request(url, { method: 'GET' }, auth);
    },

    // ========== POST ==========
    post(url, body, auth = true) {
        return this.request(url, {
            method: 'POST',
            body: JSON.stringify(body)
        }, auth);
    },

    // ========== PUT ==========
    put(url, body = {}, auth = true) {
        return this.request(url, {
            method: 'PUT',
            body: JSON.stringify(body)
        }, auth);
    },

    // ========== DELETE ==========
    del(url, auth = true) {
        return this.request(url, { method: 'DELETE' }, auth);
    }
};
