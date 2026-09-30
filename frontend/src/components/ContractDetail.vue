<script setup lang="ts">
import { onMounted, ref } from 'vue'
import BusinessDetail from './BusinessDetail.vue'
import { loadContractDetail } from '../services/receivables'
import { apiBase, useSession } from '../session'

const props = defineProps<{ id: number }>()
const session = useSession()
const data = ref<Record<string, unknown> | null>(null)
const loading = ref(false)
const error = ref('')

async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  error.value = ''
  try {
    data.value = (
      await loadContractDetail(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        props.id,
      )
    ).data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '详情加载失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <p v-if="loading" class="empty-state">加载详情中...</p>
  <p v-else-if="error" class="error-banner">{{ error }}</p>
  <BusinessDetail v-else-if="data" :data="data" />
</template>
