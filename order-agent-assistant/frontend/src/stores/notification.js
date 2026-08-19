import { defineStore } from 'pinia'
import * as notifApi from '../api/notification'

export const useNotificationStore = defineStore('notification', {
  state: () => ({
    unread: 0,
    list: [],
    timer: null,
  }),
  actions: {
    async refreshUnread() {
      try {
        this.unread = await notifApi.unreadCount()
      } catch {
        /* 静默，轮询失败等下一轮 */
      }
    },
    async loadList() {
      this.list = await notifApi.myNotifications()
      this.unread = this.list.filter((n) => n.isRead === 0).length
    },
    async markRead(id) {
      await notifApi.markRead(id)
      const item = this.list.find((n) => n.id === id)
      if (item && item.isRead === 0) {
        item.isRead = 1
        this.unread = Math.max(0, this.unread - 1)
      }
    },
    async markAllRead() {
      await notifApi.markAllRead()
      this.list.forEach((n) => (n.isRead = 1))
      this.unread = 0
    },
    /** 登录后启动 30s 轮询未读数 */
    startPolling() {
      if (this.timer) return
      this.refreshUnread()
      this.timer = setInterval(() => this.refreshUnread(), 30000)
    },
    stopPolling() {
      if (this.timer) {
        clearInterval(this.timer)
        this.timer = null
      }
    },
    reset() {
      this.stopPolling()
      this.unread = 0
      this.list = []
    },
  },
})
