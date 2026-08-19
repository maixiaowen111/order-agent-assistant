import { agentHttp } from './http'

export const agentQuery = (q, sessionId) =>
  agentHttp.get('/query', { params: { q, sessionId } })

export const agentApprove = (sessionId) =>
  agentHttp.post('/approve', null, { params: { sessionId } })
