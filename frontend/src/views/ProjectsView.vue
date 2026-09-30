<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ModalPanel from '../components/ModalPanel.vue'
import ProjectDetail from '../components/ProjectDetail.vue'
import {
  batchUpdateProjectStatus,
  loadProjectRiskRules,
  loadProjects,
  previewProjects,
  type Project,
  type ProjectImportPayload,
  type ProjectRiskRule,
  updateProject,
  updateProjectRiskRule,
} from '../services/projects'
import {
  confirmImportJob,
  retryImportJobErrors,
  type GenericImportPreview,
  type GenericImportRow,
} from '../services/imports'
import { apiBase, useSession } from '../session'
import {
  healthLevelLabel,
  importJobStatusLabel,
  importRowStatusLabel,
  projectStatusLabel,
} from '../utils/labels'
import { formatCurrency } from '../utils/number'

const session = useSession()
const base = apiBase()
const rows = ref<Project[]>([])
const rules = ref<ProjectRiskRule[]>([])
const selected = ref<number[]>([])
const managerFilter = ref('')
const file = ref<File | null>(null)
const preview = ref<GenericImportPreview<ProjectImportPayload> | null>(null)
const retryJson = ref('')
const importLoading = ref(false)
const page = ref(1)

function prevPage() {
  page.value--
  load()
}

function nextPage() {
  page.value++
  load()
}
const pageSize = ref(20)
const total = ref(0)
const error = ref('')
const message = ref('')
const edit = ref<Project | null>(null)
const editDone = ref(false)
const importModalOpen = ref(false)
const importDone = ref(false)
const rulesModalOpen = ref(false)
const ruleDone = ref(false)
const projectDetailId = ref<number | null>(null)
const anyModalOpen = computed(
  () =>
    importModalOpen.value ||
    rulesModalOpen.value ||
    edit.value !== null ||
    projectDetailId.value !== null,
)

function openImportModal() {
  file.value = null
  preview.value = null
  retryJson.value = ''
  message.value = ''
  importDone.value = false
  importModalOpen.value = true
}

function closeImportModal() {
  importModalOpen.value = false
}

function openRulesModal() {
  message.value = ''
  ruleDone.value = false
  rulesModalOpen.value = true
}

function closeRulesModal() {
  rulesModalOpen.value = false
}

function openProject(id: number) {
  projectDetailId.value = id
}

function closeProject() {
  projectDetailId.value = null
}

function closeEdit() {
  edit.value = null
  editDone.value = false
}
async function load() {
  if (!session.user.value || !session.token.value) return
  try {
    const [projects, riskRules] = await Promise.all([
      loadProjects(
        base,
        session.token.value,
        session.user.value.tenant_id,
        page.value,
        pageSize.value,
        {
          project_manager: managerFilter.value,
        },
      ),
      loadProjectRiskRules(base, session.token.value, session.user.value.tenant_id),
    ])
    rows.value = projects.data.items
    total.value = projects.data.total
    rules.value = riskRules.data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '项目数据加载失败'
  }
}
function begin(item: Project) {
  edit.value = { ...item }
  editDone.value = false
  message.value = ''
}
function choose(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  message.value = ''
  preview.value = null
  retryJson.value = ''
}
const previewActionable = computed(
  () =>
    preview.value !== null && ['preview_pending', 'preview_failed'].includes(preview.value.status),
)
async function startPreview() {
  if (!session.user.value || !session.token.value || !file.value) {
    message.value = '请选择项目 CSV 文件'
    return
  }
  importLoading.value = true
  try {
    preview.value = (
      await previewProjects(base, session.token.value, session.user.value.tenant_id, file.value)
    ).data
    message.value =
      preview.value.status === 'preview_failed'
        ? '预览校验失败，请修正失败行后重新校验。'
        : '预览完成，请核对后确认导入。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '项目导入预览失败'
  } finally {
    importLoading.value = false
  }
}
async function confirmPreview() {
  if (!session.user.value || !session.token.value || !preview.value) return
  importLoading.value = true
  try {
    preview.value = (
      await confirmImportJob<ProjectImportPayload>(
        base,
        session.token.value,
        session.user.value.tenant_id,
        preview.value.job_id,
      )
    ).data
    message.value = '导入已确认，项目清单已更新。'
    importDone.value = true
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '确认导入失败'
  } finally {
    importLoading.value = false
  }
}
async function retryErrors() {
  if (!session.user.value || !session.token.value || !preview.value) return
  try {
    preview.value = (
      await retryImportJobErrors<ProjectImportPayload>(
        base,
        session.token.value,
        session.user.value.tenant_id,
        preview.value.job_id,
        JSON.parse(retryJson.value),
      )
    ).data
    retryJson.value = ''
    message.value = '失败行已重新校验。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '失败行格式不正确'
  }
}
function retryPlaceholder(rows: Array<GenericImportRow<ProjectImportPayload>>) {
  return JSON.stringify(
    rows
      .filter((row) => row.status === 'failed')
      .map((row) => ({
        row_no: row.row_no,
        project_no: row.payload?.project_no ?? '',
        project_name: row.payload?.project_name ?? '',
        customer_name: row.payload?.customer_name ?? '',
        project_manager: row.payload?.project_manager ?? '',
        project_status: row.payload?.project_status ?? '',
        start_date: row.payload?.start_date ?? '',
        delivery_date: row.payload?.delivery_date ?? '',
        acceptance_date: row.payload?.acceptance_date ?? '',
        remark: row.payload?.remark ?? '',
      })),
    null,
    2,
  )
}
async function saveEdit() {
  if (!session.user.value || !session.token.value || !edit.value) return
  try {
    await updateProject(base, session.token.value, session.user.value.tenant_id, edit.value.id, {
      project_name: edit.value.project_name,
      customer_name: edit.value.customer_name,
      project_manager: edit.value.project_manager,
      project_status: edit.value.project_status,
      start_date: edit.value.start_date,
      delivery_date: edit.value.delivery_date,
      acceptance_date: edit.value.acceptance_date,
      remark: edit.value.remark,
    })
    message.value = '项目已更新。'
    editDone.value = true
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '项目更新失败'
  }
}
async function batch(status: string) {
  if (!session.user.value || !session.token.value || !selected.value.length) return
  try {
    const result = await batchUpdateProjectStatus(
      base,
      session.token.value,
      session.user.value.tenant_id,
      selected.value,
      status,
    )
    message.value = `已批量更新 ${result.data.updated} 个项目。`
    selected.value = []
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '项目批量更新失败'
  }
}
async function saveRule(rule: ProjectRiskRule) {
  if (!session.user.value || !session.token.value) return
  try {
    await updateProjectRiskRule(
      base,
      session.token.value,
      session.user.value.tenant_id,
      rule.rule_code,
      {
        threshold: rule.threshold,
        penalty: rule.penalty,
        max_penalty: rule.max_penalty,
        enabled: rule.enabled,
      },
    )
    message.value = `风险规则 ${rule.rule_code} 已保存。`
    ruleDone.value = true
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '风险规则保存失败'
  }
}
function changeSize() {
  page.value = 1
  load()
}
function applyManagerFilter() {
  page.value = 1
  load()
}
function clearManagerFilter() {
  managerFilter.value = ''
  page.value = 1
  load()
}
onMounted(() => {
  load()
  window.addEventListener('workspace-refresh', load)
})
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">项目资金</p>
        <h2>项目资金与风险</h2>
        <p class="lead">查看项目回款健康度；导入项目、风险规则维护、项目编辑与详情通过弹层操作。</p>
      </div>
      <span class="meta">共 {{ total }} 个项目</span>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message && !anyModalOpen" class="feedback-text">{{ message }}</p>
    <div class="page-toolbar">
      <button class="ghost-button" type="button" @click="openImportModal">导入项目</button>
      <button class="ghost-button" type="button" @click="openRulesModal">项目风险规则</button>
    </div>
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>项目清单</h3>
          <span class="meta">批量状态更新和详情追溯</span>
        </div>
        <div class="filter-bar">
          <label
            >负责人<input
              v-model.trim="managerFilter"
              placeholder="项目负责人关键字"
              @keyup.enter="applyManagerFilter"
          /></label>
          <button class="small-primary-button" type="button" @click="applyManagerFilter">
            查询
          </button>
          <button
            v-if="managerFilter"
            class="ghost-button"
            type="button"
            @click="clearManagerFilter"
          >
            清除
          </button>
        </div>
        <div class="action-group">
          <button
            v-permission="'project:manage'"
            class="small-primary-button"
            :disabled="!selected.length"
            type="button"
            @click="batch('active')"
          >
            批量启用</button
          ><button
            v-permission="'project:manage'"
            class="small-primary-button"
            :disabled="!selected.length"
            type="button"
            @click="batch('completed')"
          >
            批量完成
          </button>
        </div>
      </div>
      <div v-if="rows.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>选择</th>
              <th>项目</th>
              <th>客户/负责人</th>
              <th>合同金额</th>
              <th>应收/已收</th>
              <th>回款率</th>
              <th>风险</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in rows" :key="item.id">
              <td><input v-model="selected" type="checkbox" :value="item.id" /></td>
              <td>{{ item.project_no }}<br />{{ item.project_name }}</td>
              <td>
                {{ item.customer_name || '-' }}<br /><span class="meta">{{
                  item.project_manager || '未分派负责人'
                }}</span>
              </td>
              <td>{{ formatCurrency(item.contract_amount) }}</td>
              <td>
                {{ formatCurrency(item.receivable_amount) }}<br />{{
                  formatCurrency(item.paid_amount)
                }}
              </td>
              <td>{{ (Number(item.paid_rate) * 100).toFixed(1) }}%</td>
              <td>
                <span class="pill"
                  >{{ healthLevelLabel(item.risk_level) }} · {{ item.risk_score }}</span
                ><br /><span class="meta">{{ item.risk_items.join('；') || '暂无风险项' }}</span>
              </td>
              <td>
                <div class="action-group">
                  <button
                    v-permission="'project:manage'"
                    class="text-button"
                    type="button"
                    @click="begin(item)"
                  >
                    编辑</button
                  ><button class="text-button" type="button" @click="openProject(item.id)">
                    详情
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">
        暂无项目数据，请通过上方「导入项目」按钮导入 CSV 或从合同导入带入项目。
      </p>
      <div class="pagination-controls">
        <span
          >第 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} 页，共
          {{ total }} 条</span
        ><label
          >每页<select v-model.number="pageSize" @change="changeSize">
            <option :value="20">20</option>
            <option :value="50">50</option>
            <option :value="100">100</option></select
          >条</label
        ><button class="ghost-button" :disabled="page <= 1" type="button" @click="prevPage">
          上一页</button
        ><button
          class="ghost-button"
          :disabled="page >= Math.ceil(total / pageSize)"
          type="button"
          @click="nextPage"
        >
          下一页
        </button>
      </div>
    </article>

    <ModalPanel v-if="importModalOpen" title="导入项目" @close="closeImportModal">
      <label>CSV 文件<input accept=".csv,text/csv" type="file" @change="choose" /></label>
      <p v-if="file" class="meta">已选择：{{ file.name }}</p>
      <button
        v-permission="'project:import'"
        class="primary-button"
        :disabled="importLoading"
        type="button"
        @click="startPreview"
      >
        {{ importLoading ? '处理中...' : '预览导入' }}
      </button>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <div v-if="preview" class="import-summary">
        <strong>任务 #{{ preview.job_id }}</strong
        ><span>有效 {{ preview.success_rows }}</span
        ><span>失败 {{ preview.failed_rows }}</span
        ><span>跳过 {{ preview.skipped_rows }}</span
        ><RouterLink class="text-button" :to="`/imports/${preview.job_id}`"
          >打开任务详情</RouterLink
        >
      </div>
      <template v-if="preview && previewActionable && preview.failed_rows">
        <label
          >失败行修正 JSON<textarea
            v-model="retryJson"
            rows="5"
            :placeholder="retryPlaceholder(preview.preview_rows)"
          />
        </label>
        <button
          v-permission="'project:import'"
          class="ghost-button"
          type="button"
          @click="retryErrors"
        >
          重新校验失败行
        </button>
      </template>
      <button
        v-if="preview && previewActionable"
        v-permission="'project:import'"
        class="primary-button"
        :disabled="importLoading || preview.success_rows === 0"
        type="button"
        @click="confirmPreview"
      >
        确认导入
      </button>
      <template v-if="preview">
        <h3>导入预览行</h3>
        <p class="meta">任务 #{{ preview.job_id }} · {{ importJobStatusLabel(preview.status) }}</p>
        <div v-if="preview.preview_rows.length" class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>行号</th>
                <th>项目编号</th>
                <th>项目名称</th>
                <th>客户</th>
                <th>负责人</th>
                <th>状态</th>
                <th>开始日期</th>
                <th>行状态</th>
                <th>错误原因</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in preview.preview_rows" :key="row.id">
                <td>{{ row.row_no }}</td>
                <td>{{ row.payload?.project_no || '-' }}</td>
                <td>{{ row.payload?.project_name || '-' }}</td>
                <td>{{ row.payload?.customer_name || '-' }}</td>
                <td>{{ row.payload?.project_manager || '-' }}</td>
                <td>{{ projectStatusLabel(row.payload?.project_status) }}</td>
                <td>{{ row.payload?.start_date || '-' }}</td>
                <td>
                  <span class="pill">{{ importRowStatusLabel(row.status) }}</span>
                </td>
                <td>{{ row.error_message || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-state">本次预览没有可展示的行。</p>
      </template>
      <p class="meta import-hint">
        模板字段：项目编号、项目名称、客户名称、项目负责人、项目状态、开始/交付/验收日期、备注；同一项目编号重复导入时自动跳过。
      </p>
      <template #footer>
        <button v-if="importDone" class="primary-button" type="button" @click="closeImportModal">
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="rulesModalOpen" title="项目风险规则" @close="closeRulesModal">
      <p class="meta">规则引擎计算项目健康度，保存后作用于后续项目汇总</p>
      <div v-for="rule in rules" v-if="rules.length" :key="rule.rule_code" class="rule-row">
        <strong>{{ rule.rule_code }}</strong
        ><label>阈值<input v-model="rule.threshold" type="number" step="0.01" /></label
        ><label>扣分<input v-model="rule.penalty" type="number" step="1" /></label
        ><label class="checkbox-label"><input v-model="rule.enabled" type="checkbox" />启用</label
        ><button
          v-permission="'project:rule'"
          class="text-button"
          type="button"
          @click="saveRule(rule)"
        >
          保存
        </button>
      </div>
      <p v-else class="empty-state">暂无风险规则。</p>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <template #footer>
        <button v-if="ruleDone" class="primary-button" type="button" @click="closeRulesModal">
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="edit" title="编辑项目" @close="closeEdit">
      <label>项目名称<input v-model.trim="edit.project_name" /></label
      ><label>客户名称<input v-model.trim="edit.customer_name" /></label
      ><label>负责人<input v-model.trim="edit.project_manager" /></label
      ><label
        >状态<select v-model="edit.project_status">
          <option value="active">进行中</option>
          <option value="paused">已暂停</option>
          <option value="completed">已完成</option>
          <option value="cancelled">已取消</option>
        </select></label
      ><label>开始日期<input v-model="edit.start_date" type="date" /></label
      ><label>交付日期<input v-model="edit.delivery_date" type="date" /></label
      ><label>验收日期<input v-model="edit.acceptance_date" type="date" /></label
      ><label>备注<input v-model.trim="edit.remark" /></label>
      <div class="action-group">
        <button
          v-permission="'project:manage'"
          class="primary-button"
          type="button"
          @click="saveEdit"
        >
          保存项目</button
        ><button class="ghost-button" type="button" @click="closeEdit">取消</button>
      </div>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <template #footer>
        <button v-if="editDone" class="primary-button" type="button" @click="closeEdit">
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="projectDetailId !== null" title="项目详情" @close="closeProject">
      <ProjectDetail :id="projectDetailId" />
      <template #footer>
        <button class="primary-button" type="button" @click="closeProject">关闭</button>
      </template>
    </ModalPanel>
  </section>
</template>
