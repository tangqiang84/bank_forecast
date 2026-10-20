<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import BankIcon from '../components/BankIcon.vue'
import ModalPanel from '../components/ModalPanel.vue'
import {
  createTemplateMapping,
  deleteTemplateMapping,
  loadTemplateOverview,
  updateTemplateMapping,
  type BankTemplate,
  type TemplateField,
  type TemplateMapping,
} from '../services/templates'
import { apiBase, useSession } from '../session'
import { fieldNameLabel } from '../utils/labels'

const session = useSession()
const base = apiBase()
const templates = ref<BankTemplate[]>([])
const fields = ref<TemplateField[]>([])
const mappings = ref<TemplateMapping[]>([])
const mappingTemplate = ref<BankTemplate | null>(null)

function openMappingModal(template: BankTemplate) {
  mappingTemplate.value = template
}

function closeMappingModal() {
  mappingTemplate.value = null
}
const modalOpen = ref(false)
const editingMapping = ref<TemplateMapping | null>(null)
const formHeader = ref('')
const formField = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')

const fieldLabelMap = computed(() => {
  const map: Record<string, string> = {}
  for (const item of fields.value) map[item.field] = item.label
  return map
})

function fieldLabel(field: string) {
  return fieldLabelMap.value[field] ?? fieldNameLabel(field)
}

async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  error.value = ''
  try {
    const overview = (
      await loadTemplateOverview(base, session.token.value, session.user.value.tenant_id)
    ).data
    templates.value = overview.templates
    fields.value = overview.fields
    mappings.value = overview.mappings
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '模板配置中心加载失败'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingMapping.value = null
  formHeader.value = ''
  formField.value = fields.value[0]?.field ?? ''
  modalOpen.value = true
}

function openEdit(mapping: TemplateMapping) {
  editingMapping.value = mapping
  formHeader.value = mapping.header
  formField.value = mapping.field
  modalOpen.value = true
}

function closeModal() {
  modalOpen.value = false
  editingMapping.value = null
}

async function saveMapping() {
  if (!session.user.value || !session.token.value) return
  const header = formHeader.value.trim()
  if (!header) {
    message.value = '请填写源表头'
    return
  }
  if (!formField.value) {
    message.value = '请选择标准字段'
    return
  }
  saving.value = true
  try {
    const tenant = session.user.value.tenant_id
    if (editingMapping.value) {
      await updateTemplateMapping(base, session.token.value, tenant, editingMapping.value.id, {
        header,
        field: formField.value,
      })
      message.value = `表头映射「${header}」已更新。`
    } else {
      await createTemplateMapping(base, session.token.value, tenant, {
        header,
        field: formField.value,
      })
      message.value = `表头映射「${header}」已新增，导入流水时优先按自定义映射识别。`
    }
    closeModal()
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '表头映射保存失败'
  } finally {
    saving.value = false
  }
}

async function removeMapping(mapping: TemplateMapping) {
  if (!session.user.value || !session.token.value) return
  if (!window.confirm(`确认删除表头映射「${mapping.header}」？`)) return
  try {
    await deleteTemplateMapping(base, session.token.value, session.user.value.tenant_id, mapping.id)
    message.value = `表头映射「${mapping.header}」已删除。`
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '表头映射删除失败'
  }
}

onMounted(load)
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">模板配置中心</p>
        <h2>银行模板与表头映射</h2>
        <p class="lead">
          查看九家内置银行模板，维护租户级自定义表头映射；导入流水时自定义映射优先于内置字典。
        </p>
      </div>
      <span class="meta">共 {{ mappings.length }} 条自定义映射</span>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message" class="feedback-text">{{ message }}</p>

    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>自定义表头映射</h3>
          <span class="meta">租户级映射，按创建顺序倒序展示</span>
        </div>
        <div class="action-group">
          <span v-if="loading" class="meta">加载中...</span>
          <button
            v-permission="'template:manage'"
            class="small-primary-button"
            type="button"
            @click="openCreate"
          >
            新增映射
          </button>
        </div>
      </div>
      <div v-if="mappings.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>源表头</th>
              <th>标准字段</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="mapping in mappings" :key="mapping.id">
              <td>{{ mapping.header }}</td>
              <td>{{ fieldLabel(mapping.field) }}</td>
              <td>{{ mapping.updated_at }}</td>
              <td>
                <div class="action-group">
                  <button
                    v-permission="'template:manage'"
                    class="text-button"
                    type="button"
                    @click="openEdit(mapping)"
                  >
                    编辑</button
                  ><button
                    v-permission="'template:manage'"
                    class="text-button"
                    type="button"
                    @click="removeMapping(mapping)"
                  >
                    删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无自定义映射，新增后导入流水时优先按自定义映射识别表头。</p>
    </article>

    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>内置银行模板</h3>
          <span class="meta">九家支持银行的表头映射规则（只读，来自解析器实际规则）</span>
        </div>
      </div>
      <div class="mini-list">
        <div v-for="template in templates" :key="template.bank_code" class="mini-row">
          <span class="mini-row-identity"
            ><BankIcon :bank-code="template.bank_code" :bank-name="template.bank_name" />
            <strong>{{ template.bank_name }}（{{ template.bank_code }}）</strong></span
          >
          <span class="meta mini-row-meta">{{ template.format }} · {{ template.recognition }}</span>
          <button class="text-button" type="button" @click="openMappingModal(template)">
            字段映射
          </button>
        </div>
      </div>
    </article>

    <ModalPanel
      v-if="mappingTemplate"
      :title="`${mappingTemplate.bank_name} 字段映射`"
      @close="closeMappingModal"
    >
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>银行表头</th>
              <th>标准字段</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="mapping in mappingTemplate.field_mappings" :key="mapping.header">
              <td>{{ mapping.header }}</td>
              <td>{{ fieldLabel(mapping.field) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <template #footer>
        <button class="primary-button" type="button" @click="closeMappingModal">关闭</button>
      </template>
    </ModalPanel>

    <ModalPanel
      v-if="modalOpen"
      :title="editingMapping ? '编辑表头映射' : '新增表头映射'"
      @close="closeModal"
    >
      <label>源表头<input v-model.trim="formHeader" placeholder="例如：交易流水号" /></label
      ><label
        >标准字段<select v-model="formField">
          <option v-for="item in fields" :key="item.field" :value="item.field">
            {{ item.label }}
          </option>
        </select></label
      >
      <div class="action-group">
        <button
          v-permission="'template:manage'"
          class="primary-button"
          :disabled="saving"
          type="button"
          @click="saveMapping"
        >
          {{ editingMapping ? '保存映射' : '新增映射' }}
        </button>
        <button class="ghost-button" type="button" @click="closeModal">取消</button>
      </div>
      <p class="meta import-hint">
        同一表头只能映射一次，重复表头会被拒绝；标准字段为流水解析支持的字段全集。
      </p>
    </ModalPanel>
  </section>
</template>
