import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
  { path: '/', name: 'shop', component: () => import('../views/ShopView.vue'), meta: { requiresAuth: true } },
  { path: '/checkout', name: 'checkout', component: () => import('../views/CheckoutView.vue'), meta: { requiresAuth: true } },
  { path: '/orders', name: 'orders', component: () => import('../views/OrderListView.vue'), meta: { requiresAuth: true } },
  { path: '/notifications', name: 'notifications', component: () => import('../views/NotificationView.vue'), meta: { requiresAuth: true } },
  { path: '/admin/products', name: 'admin-products', component: () => import('../views/AdminProductView.vue'), meta: { requiresAuth: true, requiresAdmin: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return '/'
  }
  if (to.path === '/login' && auth.isLoggedIn) {
    return '/'
  }
})

export default router
