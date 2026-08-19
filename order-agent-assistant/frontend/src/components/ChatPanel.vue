<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'
import ChatMessage from './ChatMessage.vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { myOrders } from '../api/order'
import { showToast } from '../composables/useToast'

const chat = useChatStore()
const auth = useAuthStore()

const input = ref('')
const listEl = ref(null)
const textInput = ref(null)
const suggestions = ref([])

function scrollBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

// 从当前用户的订单里挖一条 PAID/WAIT_PAY 的，做成示例提示（演示 AI 取消）
async function loadSuggestions() {
  try {
    const orders = await myOrders()
    const target = orders.find((o) => o.status === 'PAID' || o.status === 'WAIT_PAY')
    suggestions.value = target
      ? [`帮我取消订单 ${target.orderNo}`, '帮我查一下我的订单']
      : ['帮我查一下我的订单', '我有哪些订单']
  } catch {
    suggestions.value = ['帮我查一下我的订单']
  }
}

watch(
  () => chat.open,
  async (open) => {
    if (!open) return
    chat.init(auth.username)
    if (chat.messages.length === 0) await loadSuggestions()
    scrollBottom()
    nextTick(() => textInput.value?.focus())
  }
)

watch(
  () => chat.messages.length,
  () => scrollBottom()
)

async function send(text) {
  const t = (text ?? input.value).trim()
  if (!t || chat.busy) return
  input.value = ''
  suggestions.value = []
  await chat.send(t)
  scrollBottom()
}

function pickSuggestion(s) {
  suggestions.value = []
  chat.send(s)
}

function newSession() {
  chat.reset()
  suggestions.value = []
  loadSuggestions()
  scrollBottom()
}

onMounted(() => chat.init(auth.username))
</script>

<template>
  <div class="chat">
    <!-- 悬浮按钮 -->
    <button class="fab" :class="{ open: chat.open }" title="AI 订单助手" @click="chat.toggle()">
      <span v-if="!chat.open">✨</span>
      <span v-else>✕</span>
    </button>

    <!-- 面板 -->
    <Transition name="panel">
      <div v-if="chat.open" class="panel">
        <div class="p-head">
          <div>
            <div class="p-title">✨ AI 订单助手</div>
            <div class="p-sub">自然语言查单 / 取消订单</div>
          </div>
          <div class="p-actions">
            <button class="icon" title="新会话" @click="newSession">↺</button>
            <button class="icon" title="收起" @click="chat.toggle()">✕</button>
          </div>
        </div>

        <div ref="listEl" class="p-list">
          <!-- 空态：引导语 + 建议 chip -->
          <div v-if="chat.messages.length === 0" class="p-empty">
            <div class="pe-emoji">👋</div>
            <p class="pe-t">你好，我是订单助手</p>
            <p class="faint">可以让我查订单、取消订单。<br />写操作会先请你确认，再真正执行。</p>
            <div class="pe-chips">
              <button
                v-for="s in suggestions"
                :key="s"
                class="pe-chip"
                @click="pickSuggestion(s)"
              >
                {{ s }}
              </button>
            </div>
          </div>

          <ChatMessage v-for="m in chat.messages" :key="m.id" :msg="m" />
        </div>

        <div class="p-input">
          <input
            ref="textInput"
            v-model="input"
            class="input"
            placeholder="输入问题，如：帮我取消订单…"
            :disabled="chat.busy"
            @keydown.enter.prevent="send()"
          />
          <button
            class="btn btn-primary send-btn"
            :disabled="chat.busy || !input.trim()"
            @click="send()"
          >
            {{ chat.busy ? '…' : '发送' }}
          </button>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.chat {
  position: fixed;
  right: 22px;
  bottom: 22px;
  z-index: 600;
}
.fab {
  width: 56px;
  height: 56px;
  border-radius: var(--r-full);
  background: var(--grad);
  color: #fff;
  font-size: 23px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow-grad);
  transition: transform var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}
.fab:hover {
  transform: translateY(-2px) scale(1.04);
}
.fab.open {
  background: var(--surface);
  color: var(--text-2);
  box-shadow: var(--shadow);
}

.panel {
  position: fixed;
  right: 22px;
  bottom: 90px;
  width: 420px;
  max-width: calc(100vw - 32px);
  height: 600px;
  max-height: calc(100vh - 120px);
  background: var(--surface);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.p-head {
  padding: 16px 18px;
  background: var(--grad);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.p-title {
  font-size: 16px;
  font-weight: 700;
}
.p-sub {
  font-size: 12px;
  opacity: 0.85;
  margin-top: 2px;
}
.p-actions {
  display: flex;
  gap: 6px;
}
.icon {
  width: 32px;
  height: 32px;
  border-radius: var(--r-sm);
  color: #fff;
  font-size: 15px;
  transition: background var(--dur) var(--ease);
}
.icon:hover {
  background: rgba(255, 255, 255, 0.2);
}
.p-list {
  flex: 1;
  overflow-y: auto;
  padding: 18px 16px 8px;
  background: var(--surface-2);
}
.p-empty {
  text-align: center;
  padding: 26px 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.pe-emoji {
  font-size: 38px;
}
.pe-t {
  font-size: 15px;
  font-weight: 700;
}
.pe-chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 10px;
}
.pe-chip {
  font-size: 13px;
  padding: 7px 14px;
  border-radius: var(--r-full);
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text-2);
  transition: all var(--dur) var(--ease);
}
.pe-chip:hover {
  color: var(--primary);
  border-color: var(--primary);
  background: var(--primary-soft);
}
.p-input {
  display: flex;
  gap: 10px;
  padding: 14px 16px;
  border-top: 1px solid var(--border);
  background: var(--surface);
}
.p-input .input {
  flex: 1;
}
.send-btn {
  padding: 0 20px;
}

/* 面板进出动画 */
.panel-enter-active,
.panel-leave-active {
  transition: opacity 0.2s var(--ease), transform 0.2s var(--ease);
}
.panel-enter-from,
.panel-leave-to {
  opacity: 0;
  transform: translateY(16px) scale(0.96);
  transform-origin: bottom right;
}
</style>
