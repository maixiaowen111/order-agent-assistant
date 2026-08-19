/** 金额：BigDecimal 序列化成数字（8999），格式化为 ¥8,999.00 */
export function formatMoney(v) {
  const n = Number(v)
  if (Number.isNaN(n)) return '¥0.00'
  return (
    '¥' +
    n.toLocaleString('zh-CN', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })
  )
}

/** 时间：LocalDateTime 序列化为 ISO（2026-07-31T13:56:31），换成空格展示 */
export function formatTime(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 19)
}

/** 聊天气泡时间 */
export function nowHHmm() {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const STATUS_META = {
  WAIT_PAY: { text: '待支付', cls: 'pill-wait' },
  PAID: { text: '已支付', cls: 'pill-paid' },
  CANCELLED: { text: '已取消', cls: 'pill-cancelled' },
}

export function statusMeta(s) {
  return STATUS_META[s] || { text: s || '未知', cls: 'pill-cancelled' }
}
