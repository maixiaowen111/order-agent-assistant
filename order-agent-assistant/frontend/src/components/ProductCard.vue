<script setup>
import { computed, ref, watch } from 'vue'
import { formatMoney } from '../utils/format'

const props = defineProps({
  product: { type: Object, required: true },
})

const emit = defineEmits(['add'])

const CAT_EMOJI = {
  手机: '📱',
  电子产品: '💻',
  电脑: '💻',
  数码: '🎧',
  食品: '🍪',
  图书: '📚',
  服饰: '👕',
}
const CAT_COLOR = [
  ['#6366f1', '#8b5cf6'],
  ['#0ea5e9', '#6366f1'],
  ['#10b981', '#0ea5e9'],
  ['#f59e0b', '#ef4444'],
  ['#ec4899', '#8b5cf6'],
]

const emoji = computed(() => CAT_EMOJI[props.product.category] || '📦')
// 用分类稳定选色（同分类同色，好看且不闪）
const grad = computed(() => {
  const s = props.product.category || ''
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  const [a, b] = CAT_COLOR[h % CAT_COLOR.length]
  return `linear-gradient(135deg, ${a}, ${b})`
})
const stockOut = computed(() => Number(props.product.stock) <= 0)

// 图片加载失败（文件被删/路径变更）→ 回退渐变占位，不破图
const imgBroken = ref(false)
watch(
  () => props.product.image,
  () => { imgBroken.value = false }
)
const showImg = computed(() => !!(props.product.image && !imgBroken.value))
function onImgError() {
  imgBroken.value = true
}
</script>

<template>
  <div class="p-card" :class="{ 'is-out': stockOut }">
    <div class="p-cover" :style="{ background: grad }">
      <img
        v-if="showImg"
        :src="product.image"
        :alt="product.name"
        class="p-img"
        @error="onImgError"
      />
      <template v-else>
        <span class="p-emoji">{{ emoji }}</span>
        <span class="p-first">{{ (product.name || '?').charAt(0) }}</span>
        <span v-if="product.category" class="p-cat">{{ product.category }}</span>
      </template>
    </div>

    <div class="p-body">
      <h3 class="p-name" :title="product.name">{{ product.name }}</h3>
      <p class="p-desc">{{ product.description || '暂无描述' }}</p>

      <div class="p-foot">
        <div>
          <div class="p-price">{{ formatMoney(product.price) }}</div>
          <div class="p-stock" :class="{ out: stockOut }">
            {{ stockOut ? '已售罄' : `库存 ${product.stock}` }}
          </div>
        </div>
        <button
          class="btn btn-primary btn-sm"
          :disabled="stockOut"
          @click="emit('add', product)"
        >
          加入购物车
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.p-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  transition: transform var(--dur) var(--ease), box-shadow var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}
.p-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-lg);
  border-color: var(--border-strong);
}
.p-card.is-out .p-cover {
  filter: grayscale(0.7);
  opacity: 0.7;
}
.p-cover {
  position: relative;
  height: 118px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.p-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.p-emoji {
  font-size: 40px;
  filter: drop-shadow(0 4px 8px rgba(0, 0, 0, 0.25));
}
.p-first {
  position: absolute;
  right: 12px;
  bottom: 8px;
  font-size: 46px;
  font-weight: 800;
  color: rgba(255, 255, 255, 0.14);
  letter-spacing: 2px;
}
.p-cat {
  position: absolute;
  top: 10px;
  left: 10px;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  padding: 2px 9px;
  border-radius: var(--r-full);
  background: rgba(0, 0, 0, 0.22);
  backdrop-filter: blur(4px);
}
.p-body {
  padding: 14px 16px 16px;
}
.p-name {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.p-desc {
  margin-top: 4px;
  font-size: 12.5px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.p-foot {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-top: 12px;
}
.p-price {
  font-size: 18px;
  font-weight: 700;
  color: var(--danger);
  letter-spacing: -0.5px;
}
.p-stock {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 2px;
}
.p-stock.out {
  color: var(--danger);
  font-weight: 600;
}
</style>
