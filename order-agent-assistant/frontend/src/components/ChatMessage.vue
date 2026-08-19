<script setup>
import { computed } from 'vue'
import { formatMoney } from '../utils/format'
import { useChatStore } from '../stores/chat'

const props = defineProps({ msg: { type: Object, required: true } })
const chat = useChatStore()

const hasChips = computed(() => props.msg.orderNo || props.msg.status || props.msg.amount)
</script>

<template>
  <div class="row" :class="msg.role">
    <div v-if="msg.role === 'agent'" class="avatar">✨</div>

    <div class="bubble-wrap">
      <div class="bubble" :class="msg.role">
        <span v-if="msg.loading" class="typing"><i></i><i></i><i></i></span>

        <template v-else>
          <div class="text">{{ msg.content }}</div>

          <!-- 结构化订单信息 chips -->
          <div v-if="hasChips" class="chips">
            <span v-if="msg.orderNo" class="chip mono">{{ msg.orderNo }}</span>
            <span v-if="msg.status" class="chip chip-status">{{ msg.status }}</span>
            <span v-if="msg.amount" class="chip">{{ formatMoney(msg.amount) }}</span>
          </div>

          <!-- ★ 批准按钮：只有被闸门拦截的那条消息会出现 -->
          <button
            v-if="msg.pendingApproval"
            class="approve-btn pulse"
            :disabled="msg.approving"
            @click="chat.approve(msg.id)"
          >
            🛡️ {{ msg.approving ? '批准中…' : '批准执行' }}
          </button>
        </template>
      </div>
      <span class="time">{{ msg.time }}</span>
    </div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}
.row.user {
  flex-direction: row-reverse;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: var(--r-full);
  background: var(--grad);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  flex-shrink: 0;
  box-shadow: var(--shadow-sm);
}
.bubble-wrap {
  max-width: 78%;
  display: flex;
  flex-direction: column;
}
.row.user .bubble-wrap {
  align-items: flex-end;
}
.bubble {
  padding: 10px 14px;
  border-radius: var(--r-lg);
  font-size: 14px;
  line-height: 1.7;
  box-shadow: var(--shadow-sm);
}
.bubble.agent {
  background: var(--surface);
  border: 1px solid var(--border);
  border-top-left-radius: 4px;
  color: var(--text);
}
.bubble.user {
  background: var(--grad);
  color: #fff;
  border-top-right-radius: 4px;
}
.text {
  white-space: pre-wrap;
  word-break: break-word;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.chip {
  font-size: 11.5px;
  padding: 2px 9px;
  border-radius: var(--r-full);
  background: var(--surface-3);
  color: var(--text-2);
}
.chip-status {
  color: var(--primary);
  background: var(--primary-soft);
  font-weight: 600;
}
.approve-btn {
  margin-top: 10px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  border-radius: var(--r-full);
  font-size: 13.5px;
  font-weight: 700;
  color: #fff;
  background: var(--grad);
  box-shadow: var(--shadow-grad);
  transition: transform var(--dur) var(--ease);
}
.approve-btn:hover:not(:disabled) {
  transform: translateY(-1px);
}
.approve-btn:disabled {
  opacity: 0.7;
  cursor: wait;
}
.time {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 4px;
}
</style>
