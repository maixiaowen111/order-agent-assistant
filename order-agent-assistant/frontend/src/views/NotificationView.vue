<script setup>
import { onMounted } from 'vue'
import { useNotificationStore } from '../stores/notification'
import { formatTime } from '../utils/format'
import { showToast } from '../composables/useToast'

const notif = useNotificationStore()

async function open(n) {
  if (n.isRead === 0) {
    try {
      await notif.markRead(n.id)
    } catch {
      /* 拦截器已 toast */
    }
  }
}

async function readAll() {
  if (notif.unread === 0) return
  try {
    await notif.markAllRead()
    showToast('已全部标为已读', 'success')
  } catch {
    /* 拦截器已 toast */
  }
}

onMounted(() => {
  notif.loadList().catch(() => {})
})
</script>

<template>
  <div class="notif">
    <div class="head">
      <div>
        <h2 class="page-title">通知中心</h2>
        <p class="page-sub">{{ notif.unread > 0 ? `${notif.unread} 条未读` : '没有未读通知' }}</p>
      </div>
      <button v-if="notif.unread > 0" class="btn btn-ghost btn-sm" @click="readAll">全部标为已读</button>
    </div>

    <div v-if="notif.list.length === 0" class="empty card">
      <span class="empty-emoji">🔔</span>
      <p>暂无通知</p>
    </div>

    <div v-else class="list">
      <div
        v-for="n in notif.list"
        :key="n.id"
        class="card notif-card"
        :class="{ unread: n.isRead === 0 }"
        @click="open(n)"
      >
        <div class="n-ic" :class="n.isRead === 0 ? 'dot' : 'done'">🔔</div>
        <div class="n-main">
          <div class="n-title-row">
            <span class="n-title">{{ n.title }}</span>
            <span v-if="n.orderNo" class="n-no mono">{{ n.orderNo }}</span>
          </div>
          <p class="n-content">{{ n.content }}</p>
          <span class="n-time">{{ formatTime(n.createTime) }}</span>
        </div>
        <span v-if="n.isRead === 0" class="unread-dot"></span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 18px;
}
.page-title {
  font-size: 22px;
  font-weight: 700;
}
.page-sub {
  color: var(--text-2);
  margin-top: 2px;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.notif-card {
  position: relative;
  display: flex;
  gap: 14px;
  padding: 16px 18px;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}
.notif-card:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow);
}
.notif-card.unread {
  border-color: rgba(99, 102, 241, 0.4);
  background: linear-gradient(90deg, rgba(99, 102, 241, 0.05), transparent 60%);
}
.n-ic {
  width: 40px;
  height: 40px;
  border-radius: var(--r-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  flex-shrink: 0;
}
.n-ic.dot {
  background: var(--primary-soft);
}
.n-ic.done {
  background: var(--surface-2);
  filter: grayscale(0.4);
  opacity: 0.7;
}
.n-main {
  flex: 1;
  min-width: 0;
}
.n-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.n-title {
  font-size: 14.5px;
  font-weight: 700;
}
.n-no {
  font-size: 11.5px;
  color: var(--primary);
  background: var(--primary-soft);
  padding: 1px 8px;
  border-radius: var(--r-full);
}
.n-content {
  font-size: 13.5px;
  color: var(--text-2);
  margin-top: 4px;
  line-height: 1.7;
}
.n-time {
  display: block;
  font-size: 12px;
  color: var(--text-3);
  margin-top: 6px;
}
.unread-dot {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 8px;
  height: 8px;
  border-radius: var(--r-full);
  background: var(--primary);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.18);
}
</style>
