<script setup lang="ts">
defineProps<{ title: string; wide?: boolean }>()
const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <div class="modal-backdrop" role="presentation">
    <section
      class="modal-panel"
      :class="{ 'modal-panel--wide': wide }"
      role="dialog"
      aria-modal="true"
      :aria-label="title"
    >
      <header class="modal-header">
        <h2>{{ title }}</h2>
        <button
          class="icon-button"
          aria-label="关闭弹层"
          title="关闭弹层"
          type="button"
          @click="emit('close')"
        >
          ×
        </button>
      </header>
      <div class="modal-body"><slot /></div>
      <footer v-if="$slots.footer" class="modal-footer"><slot name="footer" /></footer>
    </section>
  </div>
</template>
