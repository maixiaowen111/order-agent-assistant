<script setup>
import { onMounted, ref } from 'vue'
import { productPage } from '../api/product'
import ProductCard from '../components/ProductCard.vue'
import { useCartStore } from '../stores/cart'
import { showToast } from '../composables/useToast'

const cart = useCartStore()

const categories = ref([])
const activeCat = ref('')
const products = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 8
const loading = ref(false)

async function fetchPage(cat = activeCat.value, page = pageNum.value) {
  loading.value = true
  try {
    const params = { pageNum: page, pageSize, category: cat || undefined }
    const res = await productPage(params)
    products.value = res.records || []
    total.value = res.total || 0
    // 补充分类（首屏大数据量派生，不写死）
    if (res.total > 0 && categories.value.length === 0) {
      const big = await productPage({ pageNum: 1, pageSize: 100, category: cat || undefined })
      const cats = new Set((big.records || []).map((p) => p.category).filter(Boolean))
      categories.value = ['', ...cats]
    }
  } finally {
    loading.value = false
  }
}

function pickCat(cat) {
  activeCat.value = cat
  pageNum.value = 1
  fetchPage(cat, 1)
}

function goto(page) {
  pageNum.value = page
  fetchPage(activeCat.value, page)
}

async function addToCart(product) {
  try {
    await cart.add(product.id, 1)
    showToast(`已加入购物车：${product.name}`, 'success')
  } catch {
    /* 拦截器已 toast */
  }
}

const pages = () => {
  const n = Math.ceil(total.value / pageSize)
  const arr = []
  for (let i = 1; i <= n; i++) arr.push(i)
  return arr
}

onMounted(() => fetchPage())
</script>

<template>
  <div class="shop">
    <div class="shop-head">
      <div>
        <h2 class="page-title">商城</h2>
        <p class="page-sub">挑喜欢的商品，加购物车，下单支付。</p>
      </div>
    </div>

    <div class="cats">
      <button
        v-for="c in categories"
        :key="c"
        class="cat-chip"
        :class="{ on: activeCat === c }"
        @click="pickCat(c)"
      >
        {{ c === '' ? '全部' : c }}
      </button>
    </div>

    <div v-if="loading && products.length === 0" class="grid-loading">加载中…</div>

    <div v-else-if="products.length === 0" class="empty card">
      <span class="empty-emoji">🛒</span>
      <p>这个分类下还没有商品</p>
    </div>

    <div v-else class="grid">
      <ProductCard v-for="p in products" :key="p.id" :product="p" @add="addToCart" />
    </div>

    <div v-if="pages().length > 1" class="pager">
      <button class="btn btn-ghost btn-sm" :disabled="pageNum <= 1" @click="goto(pageNum - 1)">上一页</button>
      <button
        v-for="p in pages()"
        :key="p"
        class="page-num"
        :class="{ on: p === pageNum }"
        @click="goto(p)"
      >
        {{ p }}
      </button>
      <button class="btn btn-ghost btn-sm" :disabled="pageNum >= pages().length" @click="goto(pageNum + 1)">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.shop-head {
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
.cats {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}
.cat-chip {
  padding: 6px 16px;
  border-radius: var(--r-full);
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
  background: var(--surface);
  border: 1px solid var(--border);
  transition: all var(--dur) var(--ease);
}
.cat-chip:hover {
  color: var(--text);
  border-color: var(--border-strong);
}
.cat-chip.on {
  color: #fff;
  background: var(--grad);
  border-color: transparent;
  box-shadow: var(--shadow-grad);
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 18px;
}
.grid-loading {
  padding: 60px;
  text-align: center;
  color: var(--text-3);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 26px;
}
.page-num {
  min-width: 34px;
  height: 34px;
  padding: 0 6px;
  border-radius: var(--r-sm);
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-2);
  transition: all var(--dur) var(--ease);
}
.page-num:hover {
  background: var(--surface-2);
}
.page-num.on {
  background: var(--grad);
  color: #fff;
  box-shadow: var(--shadow-grad);
}
</style>
