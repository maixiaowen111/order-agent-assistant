<script setup>
import { onMounted, ref, watch } from 'vue'
import { myOrders, payOrder, cancelOrder } from '../api/order'
import OrderStatusBadge from '../components/OrderStatusBadge.vue'
import { formatMoney, formatTime } from '../utils/format'
import { useUiStore } from '../stores/ui'
import { showToast } from '../composables/useToast'

const ui = useUiStore()
const orders = ref([])
const loading = ref(true)
const expanded = ref(new Set())
const acting = ref(null)

async function load() {
  try {
    orders.value = await myOrders()
  } finally {
    loading.value = false
  }
}

function toggle(id) {
  const next = new Set(expanded.value)
  next.has(id) ? next.delete(id) : next.add(id)
  expanded.value = next
}

async function pay(o) {
  acting.value = o.id
  try {
    await payOrder(o.id)
    o.status = 'PAID'
    showToast('支付成功', 'success')
  } catch {
    /* 拦截器已 toast */
  } finally {
    acting.value = null
  }
}

async function cancel(o) {
  if (!window.confirm(`确定取消订单 ${o.orderNo} 吗？`)) return
  acting.value = o.id
  try {
    await cancelOrder(o.id)
    o.status = 'CANCELLED'
    showToast('订单已取消', 'success')
  } catch {
    /* 拦截器已 toast */
  } finally {
    acting.value = null
  }
}

// ★ AI 取消成功后 bump orderVersion → 这里自动刷新
watch(
  () => ui.orderVersion,
  () => load()
)

onMounted(load)
</script>

<template>
  <div class="orders">
    <h2 class="page-title">我的订单</h2>

    <div v-if="loading" class="empty"><span class="empty-emoji">📦</span><p>加载中…</p></div>
    <div v-else-if="orders.length === 0" class="empty card">
      <span class="empty-emoji">📭</span>
      <p>还没有订单</p>
    </div>

    <div v-else class="list">
      <div v-for="o in orders" :key="o.id" class="card order-card">
        <div class="o-head" @click="toggle(o.id)">
          <div class="o-no">
            <span class="mono faint">{{ o.orderNo }}</span>
            <span class="o-time">{{ formatTime(o.createTime) }}</span>
          </div>
          <div class="o-mid">
            <OrderStatusBadge :status="o.status" />
            <span class="o-amount">{{ formatMoney(o.totalAmount) }}</span>
          </div>
          <span class="o-caret">{{ expanded.has(o.id) ? '▾' : '▸' }}</span>
        </div>

        <Transition name="fade">
          <div v-if="expanded.has(o.id)" class="o-body">
            <div v-for="it in o.items" :key="it.productId" class="o-item">
              <span class="oi-name">{{ it.productName }} ×{{ it.quantity }}</span>
              <span class="oi-price">{{ formatMoney(it.totalPrice) }}</span>
            </div>
            <p class="o-addr muted">
              {{ o.receiverName }} · {{ o.receiverPhone }}<br />{{ o.receiverAddress }}
            </p>

            <div v-if="o.status === 'WAIT_PAY'" class="o-actions">
              <button class="btn btn-ghost btn-sm" :disabled="acting === o.id" @click="cancel(o)">取消订单</button>
              <button class="btn btn-primary btn-sm" :disabled="acting === o.id" @click="pay(o)">
                {{ acting === o.id ? '处理中…' : '立即支付' }}
              </button>
            </div>
            <p v-else-if="o.status === 'PAID'" class="hint">可打开右下角 AI 助手，说「帮我取消订单 {{ o.orderNo }}」试试</p>
            <p v-else-if="o.status === 'CANCELLED'" class="hint faint">已取消</p>
          </div>
        </Transition>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-title {
  font-size: 22px;
  font-weight: 700;
  margin-bottom: 18px;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.order-card {
  overflow: hidden;
}
.o-head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 18px;
  cursor: pointer;
  transition: background var(--dur) var(--ease);
}
.o-head:hover {
  background: var(--surface-2);
}
.o-no {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.o-time {
  font-size: 12px;
  color: var(--text-3);
}
.o-mid {
  display: flex;
  align-items: center;
  gap: 14px;
}
.o-amount {
  font-size: 16px;
  font-weight: 700;
  color: var(--danger);
}
.o-caret {
  color: var(--text-3);
  font-size: 12px;
}
.o-body {
  padding: 4px 18px 16px;
  border-top: 1px dashed var(--border);
}
.o-item {
  display: flex;
  justify-content: space-between;
  padding: 7px 0;
  font-size: 13.5px;
}
.oi-name {
  color: var(--text);
}
.oi-price {
  color: var(--text-2);
}
.o-addr {
  font-size: 12.5px;
  padding: 8px 0;
  border-top: 1px solid var(--surface-3);
  margin-top: 4px;
}
.o-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 10px;
}
.hint {
  margin-top: 10px;
  font-size: 12.5px;
  color: var(--text-2);
  background: var(--primary-soft);
  border-radius: var(--r-sm);
  padding: 8px 12px;
}
</style>
