import { reactive } from 'vue'

/** 模块级响应式 toast 列表，任何地方都能 import 使用（不依赖组件上下文） */
export const toasts = reactive([])

let seed = 0

export function showToast(message, type = 'info') {
  if (!message) return
  const id = ++seed
  toasts.push({ id, message, type })
  setTimeout(() => dismissToast(id), 3200)
}

export function dismissToast(id) {
  const i = toasts.findIndex((t) => t.id === id)
  if (i >= 0) toasts.splice(i, 1)
}
