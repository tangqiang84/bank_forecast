<script setup lang="ts">
defineProps<{ data: Record<string, unknown> }>()

function entries(value: unknown) {
  return value && typeof value === 'object' && !Array.isArray(value) ? Object.entries(value as Record<string, unknown>) : []
}

function label(key: string) {
  return ({ transaction_no: '流水号', transaction_date: '交易日期', direction: '方向', amount: '金额', balance_after: '余额', counterparty_name: '对方户名', summary: '摘要', status: '状态', match_status: '匹配状态', contract_no: '合同编号', project_name: '项目名称', customer_name: '客户名称', exception_type: '异常类型', severity: '严重级别', due_date: '到期日期' } as Record<string, string>)[key] ?? key
}

function display(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'object') return Array.isArray(value) ? `${value.length} 条记录` : '已关联'
  return String(value)
}

function list(value: unknown) {
  return Array.isArray(value) ? value as Array<Record<string, unknown>> : []
}
</script>

<template>
  <div class="business-detail">
    <template v-for="section in ['transaction', 'project', 'contract', 'summary']" :key="section">
      <section v-if="data[section] && typeof data[section] === 'object' && !Array.isArray(data[section])" class="detail-section">
        <h3>{{ section === 'transaction' ? '流水信息' : section === 'project' ? '项目信息' : section === 'contract' ? '合同信息' : '资金汇总' }}</h3>
        <dl class="detail-grid"><template v-for="[key, value] in entries(data[section])" :key="key"><dt>{{ label(key) }}</dt><dd>{{ display(value) }}</dd></template></dl>
      </section>
    </template>
    <section v-if="!data.transaction && !data.project && !data.contract && !data.summary" class="detail-section"><h3>基本信息</h3><dl class="detail-grid"><template v-for="[key, value] in entries(data)" :key="key"><dt>{{ label(key) }}</dt><dd>{{ display(value) }}</dd></template></dl></section>
    <template v-for="key in ['contracts', 'receivables', 'transactions', 'exceptions', 'allocations', 'attachments']" :key="key"><section v-if="list(data[key]).length" class="detail-section"><h3>{{ key === 'contracts' ? '关联合同' : key === 'receivables' ? '应收计划' : key === 'transactions' ? '关联流水' : key === 'exceptions' ? '关联异常' : key === 'allocations' ? '分配明细' : '附件' }}</h3><div class="mini-list"><div v-for="(item, index) in list(data[key])" :key="String(item.id ?? index)" class="mini-row"><strong>{{ display(item.title ?? item.contract_no ?? item.transaction_no ?? item.file_name ?? `记录 ${index + 1}`) }}</strong><span>{{ display(item.status ?? item.amount ?? item.description) }}</span></div></div></section></template>
    <section v-if="list(data.logs).length || list(data.audit_logs).length" class="detail-section"><h3>处理时间轴</h3><ol class="timeline"><li v-for="(item, index) in [...list(data.logs), ...list(data.audit_logs)]" :key="String(item.id ?? index)"><span class="timeline-dot" /><div><strong>{{ display(item.action ?? item.event ?? '操作记录') }}</strong><p class="meta">{{ display(item.created_at ?? item.detail ?? item.text) }}</p></div></li></ol></section>
  </div>
</template>
