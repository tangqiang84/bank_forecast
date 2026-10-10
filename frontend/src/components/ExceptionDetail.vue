<script setup lang="ts">
import { onMounted, ref } from 'vue'
import BusinessDetail from './BusinessDetail.vue'
import { explainExceptionDraft, loadExceptionDetail } from '../services/receivables'
import { apiBase, useSession } from '../session'

const props = defineProps<{ id: number }>()
const session = useSession()
const data = ref<Record<string, unknown> | null>(null)
const loading = ref(false)
const error = ref('')
const draft = ref('')
const draftSource = ref('')
const draftLoading = ref(false)
const draftMessage = ref('')

async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  error.value = ''
  try {
    data.value = (
      await loadExceptionDetail(
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

function draftSourceLabel(source: string) {
  return source === 'llm' ? '大模型' : '规则'
}

async function generateDraft() {
  if (!session.user.value || !session.token.value) return
  draftLoading.value = true
  draftMessage.value = ''
  try {
    const result = (
      await explainExceptionDraft(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        props.id,
      )
    ).data
    draft.value = result.draft
    draftSource.value = result.source
  } catch (cause) {
    draftMessage.value = cause instanceof Error ? cause.message : '解释草稿生成失败'
  } finally {
    draftLoading.value = false
  }
}
onMounted(load)
</script>

<template>
  <p v-if="loading" class="empty-state">加载详情中...</p>
  <p v-else-if="error" class="error-banner">{{ error }}</p>
  <template v-else-if="data">
    <BusinessDetail :data="data" />
    <section class="detail-section">
      <h3>解释草稿</h3>
      <p v-if="draftMessage" class="feedback-text">{{ draftMessage }}</p>
      <template v-if="draft">
        <p class="meta">来源：{{ draftSourceLabel(draftSource) }}</p>
        <textarea rows="6" readonly :value="draft" />
      </template>
      <button class="ghost-button" :disabled="draftLoading" type="button" @click="generateDraft">
        {{ draftLoading ? '生成中...' : draft ? '重新生成解释草稿' : '生成解释草稿' }}
      </button>
    </section>
  </template>
</template>
