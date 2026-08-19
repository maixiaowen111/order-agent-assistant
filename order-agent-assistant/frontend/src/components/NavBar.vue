<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useCartStore } from '../stores/cart'
import { useNotificationStore } from '../stores/notification'
import { useChatStore } from '../stores/chat'
import { showToast } from '../composables/useToast'

const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()
const notif = useNotificationStore()
const chat = useChatStore()

/** 导航项：管理员额外多一个「商品管理」入口 */
const navs = computed(() => {
  const list = [
    { name: 'shop', label: '商城', icon: '🛍️' },
    { name: 'orders', label: '我的订单', icon: '📦' },
    { name: 'notifications', label: '通知', icon: '🔔' },
  ]
  if (auth.isAdmin) list.push({ name: 'admin-products', label: '商品管理', icon: '🗂️' })
  return list
})

function isActive(name) {
  return router.currentRoute.value.name === name
}

async function handleLogout() {
  await auth.logout()
  cart.reset()
  notif.reset()
  chat.clear()
  showToast('已退出登录', 'info')
  router.push('/login')
}

function openChat() {
  chat.open = true
}
</script>

<template>
  <header class="nav">
    <div class="nav-inner">
      <div class="brand" @click="router.push('/')">
        <span class="brand-logo">🛍️</span>
        <span class="brand-name">订单助手</span>
        <span class="brand-tag">AI Agent</span>
      </div>

      <nav class="nav-links">
        <RouterLink
          v-for="n in navs"
          :key="n.name"
          :to="{ name: n.name }"
          class="nav-link"
          :class="{ active: isActive(n.name) }"
        >
          <span class="nav-ic">{{ n.icon }}</span>
          {{ n.label }}
          <span v-if="n.name === 'notifications' && notif.unread > 0" class="badge">
            {{ notif.unread > 99 ? '99+' : notif.unread }}
          </span>
        </RouterLink>
      </nav>

      <div class="nav-actions">
        <button class="icon-btn" title="AI 助手" @click="openChat">
          ✨
        </button>
        <button class="icon-btn cart-btn" title="购物车" @click="cart.openDrawer()">
          🛒
          <span v-if="cart.count > 0" class="badge">{{ cart.count }}</span>
        </button>
        <div class="user-chip">
          <span class="user-avatar">{{ (auth.username || '?').charAt(0).toUpperCase() }}</span>
          <span class="user-name">{{ auth.username }}</span>
          <span v-if="auth.isAdmin" class="role-tag">管理员</span>
          <button class="logout-btn" @click="handleLogout">退出</button>
        </div>
      </div>
    </div>
  </header>
</template>

<style scoped>
.nav {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--border);
}
.nav-inner {
  max-width: 1120px;
  margin: 0 auto;
  padding: 0 20px;
  height: 60px;
  display: flex;
  align-items: center;
  gap: 24px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}
.brand-logo {
  font-size: 22px;
}
.brand-name {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.brand-tag {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: var(--r-full);
  background: var(--grad);
  color: #fff;
}
.nav-links {
  display: flex;
  gap: 4px;
  flex: 1;
}
.nav-link {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border-radius: var(--r-md);
  font-size: 14px;
  font-weight: 500;
  color: var(--text-2);
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}
.nav-link:hover {
  color: var(--text);
  background: var(--surface-2);
}
.nav-link.active {
  color: var(--primary);
  background: var(--primary-soft);
  font-weight: 600;
}
.nav-ic {
  font-size: 15px;
}
.badge {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--r-full);
  background: var(--danger);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
}
.nav-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.icon-btn {
  position: relative;
  width: 38px;
  height: 38px;
  border-radius: var(--r-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  transition: background var(--dur) var(--ease);
}
.icon-btn:hover {
  background: var(--surface-2);
}
.cart-btn .badge {
  position: absolute;
  top: -4px;
  right: -4px;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 4px;
  padding: 5px 8px 5px 5px;
  border-radius: var(--r-full);
  background: var(--surface-2);
}
.user-avatar {
  width: 26px;
  height: 26px;
  border-radius: var(--r-full);
  background: var(--grad);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.user-name {
  font-size: 13px;
  font-weight: 600;
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.role-tag {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: var(--r-full);
  background: var(--primary-soft);
  color: var(--primary);
}
.logout-btn {
  font-size: 12px;
  color: var(--text-3);
  padding: 2px 8px;
  border-radius: var(--r-sm);
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease);
}
.logout-btn:hover {
  color: var(--danger);
  background: var(--danger-soft);
}
</style>
