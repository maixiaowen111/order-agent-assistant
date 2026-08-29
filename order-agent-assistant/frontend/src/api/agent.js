import { agentHttp } from './http'

// /query 是 POST 而不是 GET：一次提问会修改 Redis 会话（追加消息）并可能触发写操作，
// 语义上是"有副作用"的请求，不该用 GET。sessionId 省略时后端生成新的并返回。
export const agentQuery = (q, sessionId) =>
  agentHttp.post('/query', { q, sessionId })

export const agentApprove = (sessionId) =>
  agentHttp.post('/approve', { sessionId })
