<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BusinessDetail from '../components/BusinessDetail.vue'
import DetailDrawer from '../components/DetailDrawer.vue'
import { loadAccountDetail, loadTransactionDetail } from '../services/bank'
import { loadContractDetail, loadExceptionDetail, loadMatchResultDetail } from '../services/receivables'
import { loadProjectDetail } from '../services/projects'
import { loadReportDetail } from '../services/reports'
import { apiBase, useSession } from '../session'
type Kind = 'account' | 'transaction' | 'contract' | 'project' | 'match' | 'exception' | 'report'
const props = defineProps<{ kind: Kind }>(); const route = useRoute(); const router = useRouter(); const session = useSession(); const data = ref<Record<string, unknown> | null>(null); const loading = ref(false); const error = ref(''); const titles: Record<Kind, string> = { account: '账户详情', transaction: '流水详情', contract: '合同详情', project: '项目详情', match: '匹配结果详情', exception: '异常事项详情', report: '报表任务详情' }
async function load() { if (!session.user.value || !session.token.value) return; loading.value = true; error.value = ''; try { const base = apiBase(); const token = session.token.value; const tenant = session.user.value.tenant_id; const id = Number(route.params.id); if (props.kind === 'account') data.value = (await loadAccountDetail(base, token, tenant, id)).data as Record<string, unknown>; if (props.kind === 'transaction') data.value = (await loadTransactionDetail(base, token, tenant, id)).data; if (props.kind === 'contract') data.value = (await loadContractDetail(base, token, tenant, id)).data; if (props.kind === 'project') data.value = (await loadProjectDetail(base, token, tenant, id)).data; if (props.kind === 'match') data.value = (await loadMatchResultDetail(base, token, tenant, id)).data; if (props.kind === 'report') data.value = (await loadReportDetail(base, token, tenant, id)).data as Record<string, unknown>; if (props.kind === 'exception') data.value = (await loadExceptionDetail(base, token, tenant, id)).data } catch (cause) { error.value = cause instanceof Error ? cause.message : '详情加载失败' } finally { loading.value = false } }
onMounted(load)
</script>
<template><section class="page-shell"><header class="hero-band"><div><p class="eyebrow">{{ titles[kind] }}</p><h2>业务对象 #{{ route.params.id }}</h2><p class="lead">字段、关联对象和处理记录按业务区块展示。</p></div><RouterLink class="ghost-button" to="/dashboard">返回驾驶舱</RouterLink></header><DetailDrawer :title="titles[kind]" :loading="loading" :error="error" @close="router.back()"><BusinessDetail v-if="data" :data="data" /></DetailDrawer></section></template>
