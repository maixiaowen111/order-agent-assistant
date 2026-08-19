import { defineStore } from 'pinia'
import * as authApi from '../api/auth'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    username: localStorage.getItem('username') || '',
    phone: localStorage.getItem('phone') || '',
    role: localStorage.getItem('role') || '',
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isAdmin: (s) => s.role === 'ADMIN',
  },
  actions: {
    /** UserVO（含 token/username/phone）→ state + localStorage */
    setSession(user) {
      this.token = user.token || ''
      this.username = user.username || ''
      this.phone = user.phone || ''
      this.role = user.role || ''
      if (this.token) localStorage.setItem('token', this.token)
      localStorage.setItem('username', this.username)
      localStorage.setItem('phone', this.phone)
      localStorage.setItem('role', this.role)
    },
    async login(payload) {
      const user = await authApi.login(payload)
      this.setSession(user)
      return user
    },
    async register(payload) {
      return authApi.register(payload)
    },
    async logout() {
      try {
        await authApi.logout()
      } catch {
        /* 后端登出失败不阻塞本地清理 */
      }
      this.clear()
    },
    /** 清本地状态 + localStorage（不含 chat 历史，chat 由调用方按 username 清理） */
    clear() {
      this.token = ''
      this.username = ''
      this.phone = ''
      this.role = ''
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      localStorage.removeItem('phone')
      localStorage.removeItem('role')
    },
  },
})
