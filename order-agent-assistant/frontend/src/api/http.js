import axios from 'axios'
import router from '../router'
import { useAuthStore } from '../stores/auth'
import { showToast } from '../composables/useToast'

/**
 * 双 axios 实例 —— 关键设计：
 * 业务接口（/api/**）统一包 {code,message,data}，且业务错误是 HTTP 200 + body.code！
 * agent 接口（/query /approve）是裸 JSON，没有 code 字段，共用拦截器会把正常回复全误判。
 * 所以拆两个实例，各配各的拦截器。
 */

/** 业务实例：解包 Result，code!=200 报错，401 清登录态跳登录 */
export const http = axios.create({
  baseURL: '/',
  timeout: 15000,
})

http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) config.headers.Authorization = `Bearer ${auth.token}`
  return config
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 200) return body.data
      if (body.code === 401) {
        forceLogout(body.message || '登录已过期，请重新登录')
      } else {
        showToast(body.message || '请求失败', 'error')
      }
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (err) => {
    if (err.response?.status === 401) {
      forceLogout('登录已过期，请重新登录')
    } else {
      showToast(err.message || '网络错误', 'error')
    }
    return Promise.reject(err)
  }
)

/** agent 实例：裸 JSON，150s 超时（LLM 单次最长约 120s），直接返回 res.data */
export const agentHttp = axios.create({
  baseURL: '/',
  timeout: 150000,
})

// agent 接口（/query /approve）同样要带登录态：agent 后端用同一个 JWT 校验"是哪个用户在操作"，
// 否则 sessionId 无主，任何人都能冒用别人的会话和批准。
agentHttp.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) config.headers.Authorization = `Bearer ${auth.token}`
  return config
})

// 关键 bug 修复：agent 之前缺这行解包拦截器。
// 业务实例有解包（return body.data），agent 没有 → chat store 拿到的是
// axios 响应对象 {data,status,...}，res.answer / res.sessionId 全是 undefined，
// 聊天气泡永远显示「没有收到回复」。加上这行，/query /approve 直接返回数据体。
agentHttp.interceptors.response.use((res) => res.data)

function forceLogout(msg) {
  const auth = useAuthStore()
  if (auth.isLoggedIn) {
    auth.clear()
    router.push('/login')
  }
  showToast(msg, 'error')
}
