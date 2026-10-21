<script setup lang="ts">
import { computed } from 'vue'
import { importRowStatusLabel } from '../utils/labels'
import { previewColumnsFor, previewFieldValue, type WizardPreviewRow } from '../utils/importPreview'

// 导入预览行表格：/imports 向导页与 /imports/:jobId 任务详情页共用，渲染口径一致。
const props = defineProps<{
  jobType: string
  rows: WizardPreviewRow[]
}>()

const columns = computed(() => previewColumnsFor(props.jobType))
</script>

<template>
  <div class="table-scroll">
    <table>
      <thead>
        <tr>
          <th>行号</th>
          <th v-for="column in columns" :key="column.key">{{ column.label }}</th>
          <th>状态</th>
          <th>错误</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.id">
          <td>{{ row.row_no }}</td>
          <td v-for="column in columns" :key="column.key">
            {{ previewFieldValue(row, column.key) }}
          </td>
          <td>
            <span class="pill">{{ importRowStatusLabel(row.status) }}</span>
          </td>
          <td>{{ row.error_message || '-' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
