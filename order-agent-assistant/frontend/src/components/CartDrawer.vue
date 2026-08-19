<script setup>
import { computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useCartStore } from '../stores/cart'
import { formatMoney } from '../utils/format'
import { showToast } from '../composables/useToast'

const router = useRouter()
const cart = useCartStore()

const allChecked = computed({
  get() {
    return cart.items.length > 0 && cart.checkedIds.length === cart.items.length
  },
  set(v) {
    cart.checkedIds = v ? cart.items.map((i) => i.id) : []
  },
})

async function changeQty(item, delta) {
  const next = item.quantity + delta
  if (next < 1) return
  try {
    await cart.updateQty(item.id, next)
  } catch {
    /* 拦截器已 toast */
  }
}

async function remove(item) {
  await cart.remove(item.id)
  showToast('已从购物车移除', 'info')
}

async function goCheckout() {
  if (cart.checkedCount === 0) {
    showToast('请先勾选要购买的商品', 'info')
    return
  }
  cart.closeDrawer()
  router.push('/checkout')
}

watch(
  () => cart.drawerOpen,
  (open) => {
    if (open) cart.load().catch(() => {})
  }
)

onMounted(() => {
  if (cart.drawerOpen) cart.load().catch(() => {})
})
</script>

<template>
  <Teleport to="body">
    <Transition name="mask">
      <div v-if="cart.drawerOpen" class="mask" @click.self="cart.closeDrawer()">
        <Transition name="slide" appear>
          <aside class="drawer">
            <div class="drawer-head">
              <h3>购物车</h3>
              <button class="close" @click="cart.closeDrawer()">✕</button>
            </div>

            <div v-if="cart.items.length === 0" class="empty">
              <span class="empty-emoji">🛒</span>
              <p>购物车是空的，去商城逛逛吧</p>
            </div>

            <div v-else class="drawer-body">
              <label class="check-all">
                <input v-model="allChecked" type="checkbox" />
                <span>全选</span>
                <span class="faint">共 {{ cart.count }} 件</span>
              </label>

              <div v-for="item in cart.items" :key="item.id" class="item">
                <input
                  v-model="cart.checkedIds"
                  type="checkbox"
                  :value="item.id"
                />
                <div class="item-main">
                  <div class="item-name">{{ item.productName }}</div>
                  <div class="item-price">{{ formatMoney(item.productPrice) }}</div>
                </div>
                <div class="qty">
                  <button class="qty-btn" :disabled="item.quantity <= 1" @click="changeQty(item, -1)">−</button>
                  <span class="qty-num">{{ item.quantity }}</span>
                  <button class="qty-btn" @click="changeQty(item, 1)">+</button>
                </div>
                <button class="del" title="移除" @click="remove(item)">🗑️</button>
              </div>
            </div>

            <div class="drawer-foot">
              <div class="foot-total">
                合计
                <span class="total-num">{{ formatMoney(cart.total) }}</span>
              </div>
              <button class="btn btn-primary btn-lg" :disabled="cart.checkedCount === 0" @click="goCheckout">
                去结算 ({{ cart.checkedCount }})
              </button>
            </div>
          </aside>
        </Transition>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  backdrop-filter: blur(2px);
  z-index: 500;
}
.drawer {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 400px;
  max-width: 92vw;
  background: var(--surface);
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-lg);
}
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px;
  border-bottom: 1px solid var(--border);
}
.drawer-head h3 {
  font-size: 16px;
  font-weight: 700;
}
.close {
  width: 32px;
  height: 32px;
  border-radius: var(--r-sm);
  color: var(--text-2);
  font-size: 15px;
  transition: background var(--dur) var(--ease);
}
.close:hover {
  background: var(--surface-2);
}
.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 20px 20px;
}
.check-all {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
  font-weight: 600;
  padding: 6px 0 12px;
  cursor: pointer;
}
.check-all .faint {
  margin-left: auto;
  font-weight: 400;
}
.item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid var(--border);
}
.item input,
.check-all input {
  width: 17px;
  height: 17px;
  accent-color: var(--primary);
  cursor: pointer;
}
.item-main {
  flex: 1;
  min-width: 0;
}
.item-name {
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.item-price {
  font-size: 13px;
  color: var(--danger);
  font-weight: 600;
  margin-top: 2px;
}
.qty {
  display: flex;
  align-items: center;
  gap: 6px;
}
.qty-btn {
  width: 24px;
  height: 24px;
  border-radius: var(--r-sm);
  background: var(--surface-2);
  color: var(--text-2);
  font-size: 15px;
  line-height: 1;
  transition: background var(--dur) var(--ease);
}
.qty-btn:hover:not(:disabled) {
  background: var(--surface-3);
}
.qty-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.qty-num {
  min-width: 22px;
  text-align: center;
  font-size: 14px;
  font-weight: 600;
}
.del {
  padding: 4px;
  font-size: 14px;
  opacity: 0.6;
  transition: opacity var(--dur) var(--ease), transform var(--dur) var(--ease);
}
.del:hover {
  opacity: 1;
  transform: scale(1.1);
}
.drawer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-top: 1px solid var(--border);
  background: var(--surface-2);
}
.foot-total {
  font-size: 13px;
  color: var(--text-2);
}
.total-num {
  margin-left: 4px;
  font-size: 20px;
  font-weight: 700;
  color: var(--danger);
}

/* 抽屉动画 */
.slide-enter-active,
.slide-leave-active {
  transition: transform 0.28s var(--ease);
}
.slide-enter-from,
.slide-leave-to {
  transform: translateX(100%);
}
.mask-enter-active,
.mask-leave-active {
  transition: opacity 0.28s var(--ease);
}
.mask-enter-from,
.mask-leave-to {
  opacity: 0;
}
</style>
