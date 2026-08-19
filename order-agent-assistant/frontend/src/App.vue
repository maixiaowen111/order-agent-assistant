<script setup>
import { onMounted, watch } from 'vue'
import NavBar from './components/NavBar.vue'
import CartDrawer from './components/CartDrawer.vue'
import ChatPanel from './components/ChatPanel.vue'
import BaseToast from './components/BaseToast.vue'
import { useAuthStore } from './stores/auth'
import { useNotificationStore } from './stores/notification'

const auth = useAuthStore()
const notif = useNotificationStore()

// 登录后全局轮询未读数（NavBar 角标），退出即停
onMounted(() => {
  if (auth.isLoggedIn) notif.startPolling()
})
watch(
  () => auth.isLoggedIn,
  (v) => (v ? notif.startPolling() : notif.stopPolling())
)
</script>

<template>
  <div class="app-root">
    <template v-if="auth.isLoggedIn">
      <NavBar />
      <main class="app-main">
        <RouterView v-slot="{ Component }">
          <Transition name="fade" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
      <CartDrawer />
      <ChatPanel />
    </template>
    <RouterView v-else />
    <BaseToast />
  </div>
</template>

<style scoped>
.app-root {
  min-height: 100vh;
}
.app-main {
  max-width: 1120px;
  margin: 0 auto;
  padding: 24px 20px 110px;
}
</style>
