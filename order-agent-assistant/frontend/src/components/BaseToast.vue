<script setup>
import { toasts, dismissToast } from '../composables/useToast'
</script>

<template>
  <div class="toast-wrap">
    <TransitionGroup name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="`toast-${t.type}`" @click="dismissToast(t.id)">
        <span class="toast-ic">{{ t.type === 'success' ? '✓' : t.type === 'error' ? '✕' : 'ℹ' }}</span>
        <span>{{ t.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-wrap {
  position: fixed;
  top: 18px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 2000;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}
.toast {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 9px 16px;
  border-radius: var(--r-md);
  background: #1e2433;
  color: #fff;
  font-size: 13.5px;
  font-weight: 500;
  box-shadow: var(--shadow-lg);
  cursor: pointer;
  pointer-events: auto;
  max-width: 70vw;
}
.toast-ic {
  font-weight: 700;
}
.toast-success .toast-ic {
  color: #34d399;
}
.toast-error .toast-ic {
  color: #f87171;
}
.toast-info .toast-ic {
  color: #93c5fd;
}
.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.25s var(--ease), transform 0.25s var(--ease);
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}
</style>
