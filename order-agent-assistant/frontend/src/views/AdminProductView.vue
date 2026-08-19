<script setup>
import { onMounted, reactive, ref } from 'vue'
import { adminProductPage, createProduct, updateProduct, updateProductStatus } from '../api/product'
import { formatMoney } from '../utils/format'
import { showToast } from '../composables/useToast'

const products = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 20
const loading = ref(false)

// —— 新增/编辑 共用模态框 ——
const modalOpen = ref(false)
const saving = ref(false)
const editing = ref(null) // null=新增，非 null=编辑该商品
const form = reactive({ name: '', category: '', price: '', stock: '', description: '' })

async function fetchPage(page = pageNum.value) {
  loading.value = true
  try {
    const res = await adminProductPage({ pageNum: page, pageSize })
    products.value = res.records || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

function goto(page) {
  pageNum.value = page
  fetchPage(page)
}

const pages = () => {
  const n = Math.ceil(total.value / pageSize)
  return Array.from({ length: n }, (_, i) => i + 1)
}

function openCreate() {
  editing.value = null
  form.name = ''
  form.category = ''
  form.price = ''
  form.stock = ''
  form.description = ''
  modalOpen.value = true
}

function openEdit(p) {
  editing.value = p
  form.name = p.name || ''
  form.category = p.category || ''
  form.price = p.price ?? ''
  form.stock = p.stock ?? ''
  form.description = p.description || ''
  modalOpen.value = true
}

async function save() {
  if (!form.name.trim()) return showToast('请填写商品名称', 'error')
  if (form.price === '' || !(form.price > 0)) return showToast('价格必须大于 0', 'error')
  if (form.stock === '' || !(form.stock >= 0)) return showToast('库存不能小于 0', 'error')

  saving.value = true
  const payload = {
    name: form.name.trim(),
    category: form.category.trim() || null,
    price: Number(form.price),
    stock: Number(form.stock),
    description: form.description.trim() || null,
  }
  try {
    if (editing.value) {
      await updateProduct(editing.value.id, payload)
      showToast(`已保存：${payload.name}`, 'success')
    } else {
      await createProduct(payload)
      showToast(`已新增商品：${payload.name}`, 'success')
    }
    modalOpen.value = false
    fetchPage()
  } catch {
    /* 拦截器已 toast */
  } finally {
    saving.value = false
  }
}

async function toggleStatus(p) {
  const next = p.status === 1 ? 0 : 1
  try {
    await updateProductStatus(p.id, next)
    showToast(next === 1 ? `已上架：${p.name}` : `已下架：${p.name}`, 'success')
    fetchPage()
  } catch {
    /* 拦截器已 toast */
  }
}

onMounted(() => fetchPage())
</script>

<template>
  <div class="admin">
    <div class="admin-head">
      <div>
        <h2 class="page-title">商品管理</h2>
        <p class="page-sub">管理员专属：新增 / 编辑 / 上下架商品，含已下架商品。</p>
      </div>
      <button class="btn btn-primary" @click="openCreate">＋ 新增商品</button>
    </div>

    <div v-if="loading && products.length === 0" class="table-loading">加载中…</div>

    <div v-else-if="products.length === 0" class="empty card">
      <span class="empty-emoji">🗂️</span>
      <p>还没有商品，点右上角「新增商品」开始</p>
    </div>

    <div v-else class="card table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>分类</th>
            <th class="num">价格</th>
            <th class="num">库存</th>
            <th>状态</th>
            <th class="ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in products" :key="p.id">
            <td>
              <div class="p-name">{{ p.name }}</div>
              <div class="p-desc">{{ p.description || '—' }}</div>
            </td>
            <td>{{ p.category || '—' }}</td>
            <td class="num">{{ formatMoney(p.price) }}</td>
            <td class="num" :class="{ low: p.stock <= 0 }">{{ p.stock }}</td>
            <td>
              <span class="st" :class="p.status === 1 ? 'on' : 'off'">
                {{ p.status === 1 ? '上架' : '下架' }}
              </span>
            </td>
            <td class="ops">
              <button class="btn btn-ghost btn-sm" @click="openEdit(p)">编辑</button>
              <button class="btn btn-soft btn-sm" @click="toggleStatus(p)">
                {{ p.status === 1 ? '下架' : '上架' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
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

    <!-- 新增/编辑 模态框（复刻 CartDrawer 的 Teleport+mask 范式，改居中） -->
    <Teleport to="body">
      <Transition name="mask">
        <div v-if="modalOpen" class="modal-mask" @click.self="modalOpen = false">
          <Transition name="pop" appear>
            <div class="modal">
              <div class="modal-head">
                <h3>{{ editing ? '编辑商品' : '新增商品' }}</h3>
                <button class="close" @click="modalOpen = false">✕</button>
              </div>

              <div class="modal-body">
                <div class="field">
                  <label class="label">商品名称 *</label>
                  <input v-model="form.name" class="input" placeholder="如：无线机械键盘" maxlength="200" />
                </div>
                <div class="field">
                  <label class="label">分类</label>
                  <input v-model="form.category" class="input" placeholder="如：数码 / 服饰 / 食品" maxlength="50" />
                </div>
                <div class="field-row">
                  <div class="field">
                    <label class="label">价格（元）*</label>
                    <input v-model.number="form.price" class="input" type="number" min="0" step="0.01" placeholder="0.00" />
                  </div>
                  <div class="field">
                    <label class="label">库存 *</label>
                    <input v-model.number="form.stock" class="input" type="number" min="0" step="1" placeholder="0" />
                  </div>
                </div>
                <div class="field">
                  <label class="label">描述</label>
                  <textarea v-model="form.description" class="textarea" rows="3" placeholder="商品卖点、规格说明…"></textarea>
                </div>
              </div>

              <div class="modal-foot">
                <button class="btn btn-ghost" :disabled="saving" @click="modalOpen = false">取消</button>
                <button class="btn btn-primary" :disabled="saving" @click="save">
                  {{ saving ? '保存中…' : editing ? '保存修改' : '创建商品' }}
                </button>
              </div>
            </div>
          </Transition>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.admin-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
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
.table-wrap {
  overflow: hidden;
}
.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13.5px;
}
.table th {
  text-align: left;
  padding: 12px 16px;
  background: var(--surface-2);
  color: var(--text-2);
  font-size: 12.5px;
  font-weight: 600;
  white-space: nowrap;
}
.table td {
  padding: 12px 16px;
  border-top: 1px solid var(--border);
  vertical-align: middle;
}
.table th.num,
.table td.num {
  text-align: right;
  white-space: nowrap;
}
.table th.ops,
.table td.ops {
  text-align: right;
  white-space: nowrap;
}
.p-name {
  font-weight: 600;
}
.p-desc {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 2px;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.low {
  color: var(--danger);
  font-weight: 600;
}
.st {
  display: inline-block;
  font-size: 12px;
  font-weight: 700;
  padding: 3px 10px;
  border-radius: var(--r-full);
}
.st.on {
  color: var(--success);
  background: var(--success-soft);
}
.st.off {
  color: var(--text-3);
  background: var(--surface-3);
}
.ops .btn + .btn {
  margin-left: 6px;
}
.table-loading {
  padding: 60px;
  text-align: center;
  color: var(--text-3);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 22px;
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

/* —— 模态框 —— */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(3px);
  z-index: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.modal {
  width: 440px;
  max-width: 100%;
  background: var(--surface);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border);
}
.modal-head h3 {
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
.modal-body {
  padding: 18px 20px 6px;
}
.field-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px 18px;
}

/* 过渡动画 */
.mask-enter-active,
.mask-leave-active {
  transition: opacity 0.2s var(--ease);
}
.mask-enter-from,
.mask-leave-to {
  opacity: 0;
}
.pop-enter-active,
.pop-leave-active {
  transition: transform 0.22s var(--ease), opacity 0.22s var(--ease);
}
.pop-enter-from,
.pop-leave-to {
  opacity: 0;
  transform: translateY(14px) scale(0.96);
}
</style>
