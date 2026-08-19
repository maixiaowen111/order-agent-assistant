/**
 * agent 相关的判定工具。
 * 「写操作被拦截」是 WritePermissionGate 代码里写死的确定性文案，模型几乎会原样转述，
 * 命中率最高；其余关键词 + 「用户说取消且回复说确认」作为兜底。
 */

const CONFIRM_KW = ['人工确认', '需要确认', '人工客服确认', '写操作被拦截']

/** 订单号：14 位时间戳 + 4 位以上 hex，如 20260819104345b78ccb */
const ORDER_NO_RE = /\b\d{14}[0-9a-f]{4,}\b/i

export function shouldShowApprove(answer, lastUserText) {
  const a = answer || ''
  if (CONFIRM_KW.some((k) => a.includes(k))) return true
  if ((lastUserText || '').includes('取消') && a.includes('确认')) return true
  return false
}

export function extractOrderNo(text) {
  const m = (text || '').match(ORDER_NO_RE)
  return m ? m[0] : ''
}

/** 用户原话是否表达取消意图（用于兜底：模型没走取消路径时把它拉回来） */
export function hasCancelIntent(text) {
  return /取消|退单|退款/.test(text || '')
}

/** 取消是否真正生效（决定订单列表要不要刷新） */
export function isCancelSuccess(answer, status) {
  if ((status || '') === 'CANCELLED') return true
  return /已成功取消|已取消|取消成功/.test(answer || '')
}
