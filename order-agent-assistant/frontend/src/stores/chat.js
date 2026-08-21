import { defineStore } from 'pinia'
import * as agentApi from '../api/agent'
import { shouldShowApprove, extractOrderNo, isCancelSuccess, hasCancelIntent } from '../utils/agent'
import { nowHHmm } from '../utils/format'
import { useUiStore } from './ui'

let msgSeed = 0

/**
 * AI 面板状态机。
 * sessionId 是锚点：首次 /query 不传，服务端生成返回，前端存下后所有 /query 和 /approve
 * 都回传同一值 —— 多轮记忆和批准状态都按它绑定。
 *
 * 批准流：answer 含「人工确认/写操作被拦截」→ 消息挂 pendingApproval → 渲染批准按钮
 * → 点击：POST /approve 放行，再补一句「我已批准，请继续执行你刚才要执行的写操作」重发
 * /query，agent 真正执行（取消 / 改地址都走这里）→ 若是取消成功则订单列表刷新。
 */
export const useChatStore = defineStore('chat', {
  state: () => ({
    open: false,
    busy: false,
    username: '',
    sessionId: '',
    messages: [],
  }),
  actions: {
    /** 进入页面时按用户名加载持久化会话 */
    init(username) {
      if (username && username !== this.username) {
        this.clear()
        this.username = username
        const raw = localStorage.getItem(`chat:${username}`)
        if (raw) {
          try {
            const d = JSON.parse(raw)
            this.sessionId = d.sessionId || ''
            this.messages = d.messages || []
          } catch {
            /* 损坏数据当新会话 */
          }
        }
      }
    },
    persist() {
      if (!this.username) return
      localStorage.setItem(
        `chat:${this.username}`,
        JSON.stringify({ sessionId: this.sessionId, messages: this.messages })
      )
    },
    toggle() {
      this.open = !this.open
    },
    /** 发送普通提问（触发取消也走这里） */
    async send(text) {
      text = (text || '').trim()
      if (!text || this.busy) return
      this.busy = true
      this.push({ role: 'user', content: text })

      const loadingId = this.push({ role: 'agent', loading: true })
      try {
        let res = await agentApi.agentQuery(text, this.sessionId || undefined)
        this.sessionId = res.sessionId || this.sessionId

        // 取消意图兜底：模型偶尔把「取消」误判成「查单」，闸门就拦不到、批准按钮出不来。
        // 检测到这种情况，自动补一句命令把它拉回取消路径（中间那轮不展示，不污染对话）。
        const orderNo = res.orderNo || extractOrderNo(text)
        if (hasCancelIntent(text) && orderNo &&
            !shouldShowApprove(res.answer || '', text) &&
            !isCancelSuccess(res.answer || '', res.status)) {
          const steer = `请立即调用 cancel_order 工具执行取消订单 ${orderNo}，不要再查单，不要再询问确认。`
          res = await agentApi.agentQuery(steer, this.sessionId)
        }

        const answer = res.answer || '没有收到回复'
        this.update(loadingId, {
          loading: false,
          content: answer,
          orderNo: res.orderNo || '',
          status: res.status || '',
          amount: res.amount || '',
          pendingApproval: shouldShowApprove(answer, text),
        })
      } catch (e) {
        this.update(loadingId, { loading: false, content: e.message || '调用失败' })
      } finally {
        this.busy = false
        this.persist()
      }
    },
    /** 点击「批准执行」：放行闸门 → 自动补一句让 agent 真正执行 */
    async approve(msgId) {
      const msg = this.messages.find((m) => m.id === msgId)
      if (!msg || !this.sessionId || this.busy) return

      this.busy = true
      msg.approving = true
      msg.pendingApproval = false
      try {
        await agentApi.agentApprove(this.sessionId)
        msg.approved = true
        msg.approving = false

        // follow-up 不点名工具：可能是取消、也可能是改地址。模型知道自己刚才在做什么，
        // 泛化指令「继续执行你刚才要执行的写操作」让它调用对应的工具，避免把改地址误拉成取消。
        const followUp = '我已批准，请继续执行你刚才要执行的写操作'
        this.push({ role: 'user', content: followUp })
        const loadingId = this.push({ role: 'agent', loading: true })
        const res = await agentApi.agentQuery(followUp, this.sessionId)
        const answer = res.answer || '没有收到回复'
        this.update(loadingId, {
          loading: false,
          content: answer,
          orderNo: res.orderNo || msg.orderNo || '',
          status: res.status || '',
          amount: res.amount || '',
        })

        // 取消真正生效 → 订单列表自动刷新
        if (isCancelSuccess(answer, res.status)) {
          useUiStore().bumpOrderVersion()
        }
      } catch (e) {
        msg.approving = false
        msg.pendingApproval = true
        this.push({ role: 'agent', content: `批准失败：${e.message || e}，请重试。` })
      } finally {
        this.busy = false
        this.persist()
      }
    },
    /** 新会话：清掉 sessionId + 消息 */
    reset() {
      this.sessionId = ''
      this.messages = []
      this.persist()
    },
    /** 完全清理（切用户/退出登录时） */
    clear() {
      this.open = false
      this.busy = false
      this.sessionId = ''
      this.messages = []
      if (this.username) localStorage.removeItem(`chat:${this.username}`)
      this.username = ''
    },
    push(m) {
      const id = `m${Date.now()}_${msgSeed++}`
      this.messages.push({ id, time: nowHHmm(), ...m })
      return id
    },
    update(id, patch) {
      const m = this.messages.find((x) => x.id === id)
      if (m) Object.assign(m, patch)
    },
    lastUserText() {
      return [...this.messages].reverse().find((m) => m.role === 'user')?.content || ''
    },
  },
})
