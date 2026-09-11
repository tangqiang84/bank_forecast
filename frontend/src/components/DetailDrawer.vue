<script setup lang="ts">
defineProps<{ title: string; loading?: boolean; error?: string }>()
const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <div class="drawer-backdrop" role="presentation" @click.self="emit('close')">
    <aside class="detail-drawer" aria-label="详情抽屉">
      <header class="drawer-header"><div><p class="eyebrow">业务详情</p><h2>{{ title }}</h2></div><button class="icon-button" aria-label="关闭详情" title="关闭详情" type="button" @click="emit('close')">×</button></header>
      <p v-if="loading" class="empty-state">加载详情中...</p>
      <p v-else-if="error" class="error-banner">{{ error }}</p>
      <div v-else class="drawer-content"><slot /></div>
    </aside>
  </div>
</template>
