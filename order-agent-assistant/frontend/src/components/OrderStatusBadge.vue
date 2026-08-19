<script setup>
import { computed } from 'vue'
import { statusMeta } from '../utils/format'

const props = defineProps({ status: { type: String, default: '' } })
const meta = computed(() => statusMeta(props.status))
</script>

<template>
  <span class="pill" :class="meta.cls">
    <span class="pill-dot"></span>
    {{ meta.text }}
  </span>
</template>
