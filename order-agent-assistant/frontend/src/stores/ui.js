import { defineStore } from 'pinia'

/**
 * 跨页面 UI 状态。
 * orderVersion：AI 取消订单成功后 bump，订单列表 watch 到就自动刷新，
 * 不用手动 F5 就能看到状态从 已支付 → 已取消。
 */
export const useUiStore = defineStore('ui', {
  state: () => ({ orderVersion: 0 }),
  actions: {
    bumpOrderVersion() {
      this.orderVersion++
    },
  },
})
