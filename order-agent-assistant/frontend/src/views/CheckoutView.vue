<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createOrder, payOrder } from '../api/order'
import { useCartStore } from '../stores/cart'
import { formatMoney, formatTime } from '../utils/format'
import { showToast } from '../composables/useToast'

const router = useRouter()
const cart = useCartStore()

const checkoutItems = computed(() =>
  cart.items.filter((it) => cart.checkedIds.includes(it.id))
)

const form = reactive({
  receiverName: localStorage.getItem('addr_name') || '',
  receiverPhone: localStorage.getItem('addr_phone') || '',
  receiverAddress: localStorage.getItem('addr_addr') || '',
})

const submitting = ref(false)
const order = ref(null) // 下单成功后的 OrderVO

async function submit() {
  if (checkoutItems.value.length === 0) {
    showToast('购物车没有可结算的商品', 'info')
    return
  }
  if (!form.receiverName || !form.receiverPhone || !form.receiverAddress) {
    showToast('请填写完整收货信息', 'error')
    return
  }
  submitting.value = true
  try {
    const res = await createOrder({
      cartIds: cart.checkedIds,
      ...form,
    })
    order.value = res
    // 记住地址
    localStorage.setItem('addr_name', form.receiverName)
    localStorage.setItem('addr_phone', form.receiverPhone)
    localStorage.setItem('addr_addr', form.receiverAddress)
    // 这些商品已进订单，从购物车清掉
    cart.checkedIds = []
    await cart.load()
  } catch {
    /* 拦截器已 toast */
  } finally {
    submitting.value = false
  }
}

async function pay() {
  if (!order.value) return
  try {
    await payOrder(order.value.id)
    showToast('支付成功', 'success')
    router.push('/orders')
  } catch {
    /* 拦截器已 toast */
  }
}
</script>

<template>
  <div class="checkout">
    <h2 class="page-title">确认订单</h2>

    <!-- 下单成功态 -->
    <div v-if="order" class="card ok-card">
      <div class="ok-ic">✅</div>
      <h3>订单创建成功</h3>
      <p class="ok-no mono">{{ order.orderNo }}</p>
      <p class="ok-amount">{{ formatMoney(order.totalAmount) }}</p>
      <p class="muted">收货人：{{ order.receiverName }} · {{ order.receiverPhone }}</p>
      <p class="muted">{{ order.receiverAddress }}</p>
      <div class="ok-actions">
        <button class="btn btn-primary btn-lg" @click="pay">立即支付</button>
        <button class="btn btn-ghost btn-lg" @click="router.push('/orders')">稍后再说</button>
      </div>
    </div>

    <template v-else>
      <div v-if="checkoutItems.length === 0" class="empty card">
        <span class="empty-emoji">🧾</span>
        <p>没有可结算的商品</p>
        <button class="btn btn-primary" @click="router.push('/')">去商城逛逛</button>
      </div>

      <div v-else class="grid-2">
        <div class="card items-card">
          <h4 class="card-title">商品清单</h4>
          <div v-for="it in checkoutItems" :key="it.id" class="line">
            <span class="line-name">{{ it.productName }} ×{{ it.quantity }}</span>
            <span class="line-price">{{ formatMoney(it.totalPrice) }}</span>
          </div>
          <div class="line total-line">
            <span>合计</span>
            <span class="total">{{ formatMoney(cart.total) }}</span>
          </div>
        </div>

        <div class="card form-card">
          <h4 class="card-title">收货信息</h4>
          <div class="field">
            <label class="label">收货人</label>
            <input v-model="form.receiverName" class="input" placeholder="姓名" />
          </div>
          <div class="field">
            <label class="label">手机号</label>
            <input v-model="form.receiverPhone" class="input" placeholder="用于接收退款通知" />
          </div>
          <div class="field">
            <label class="label">收货地址</label>
            <textarea v-model="form.receiverAddress" class="textarea" placeholder="省市区 + 详细地址" />
          </div>
          <button class="btn btn-primary btn-lg btn-block" :disabled="submitting" @click="submit">
            {{ submitting ? '提交中…' : `提交订单 · ${formatMoney(cart.total)}` }}
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page-title {
  font-size: 22px;
  font-weight: 700;
  margin-bottom: 18px;
}
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
  align-items: start;
}
.items-card,
.form-card {
  padding: 20px;
}
.card-title {
  font-size: 15px;
  font-weight: 700;
  margin-bottom: 14px;
}
.line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  font-size: 14px;
}
.line-name {
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.line-price {
  color: var(--text-2);
}
.total-line {
  border-top: 1px dashed var(--border);
  margin-top: 8px;
  padding-top: 12px;
  font-weight: 600;
}
.total {
  color: var(--danger);
  font-size: 17px;
  font-weight: 700;
}
.ok-card {
  max-width: 480px;
  margin: 40px auto;
  padding: 40px 32px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.ok-ic {
  font-size: 44px;
}
.ok-card h3 {
  font-size: 19px;
  font-weight: 700;
}
.ok-no {
  font-size: 13px;
  color: var(--primary);
  background: var(--primary-soft);
  padding: 4px 12px;
  border-radius: var(--r-sm);
}
.ok-amount {
  font-size: 26px;
  font-weight: 800;
  color: var(--danger);
  margin: 6px 0;
}
.ok-actions {
  display: flex;
  gap: 12px;
  margin-top: 14px;
}
@media (max-width: 760px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
</style>
