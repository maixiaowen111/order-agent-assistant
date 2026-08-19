import { defineStore } from 'pinia'
import * as cartApi from '../api/cart'

export const useCartStore = defineStore('cart', {
  state: () => ({
    items: [],
    drawerOpen: false,
    checkedIds: [], // 勾选去结算的 cartId 列表
  }),
  getters: {
    count: (s) => s.items.reduce((n, it) => n + it.quantity, 0),
    total: (s) =>
      s.items
        .filter((it) => s.checkedIds.includes(it.id))
        .reduce((n, it) => n + it.totalPrice, 0),
    checkedCount: (s) => s.checkedIds.length,
  },
  actions: {
    async load() {
      this.items = await cartApi.myCart()
      // 清掉已不存在项的勾选
      const ids = new Set(this.items.map((i) => i.id))
      this.checkedIds = this.checkedIds.filter((id) => ids.has(id))
    },
    async add(productId, quantity = 1) {
      await cartApi.addCart({ productId, quantity })
      await this.load()
    },
    async updateQty(cartId, quantity) {
      await cartApi.updateQty(cartId, quantity)
      await this.load()
    },
    async remove(cartId) {
      await cartApi.removeCart(cartId)
      this.checkedIds = this.checkedIds.filter((id) => id !== cartId)
      await this.load()
    },
    async removeChecked() {
      await Promise.all(this.checkedIds.map((id) => cartApi.removeCart(id)))
      this.checkedIds = []
      await this.load()
    },
    toggleCheck(id) {
      this.checkedIds.includes(id)
        ? (this.checkedIds = this.checkedIds.filter((x) => x !== id))
        : (this.checkedIds = [...this.checkedIds, id])
    },
    setCheckedAll() {
      this.checkedIds = this.items.map((i) => i.id)
    },
    openDrawer() {
      this.drawerOpen = true
    },
    closeDrawer() {
      this.drawerOpen = false
    },
    reset() {
      this.items = []
      this.drawerOpen = false
      this.checkedIds = []
    },
  },
})
