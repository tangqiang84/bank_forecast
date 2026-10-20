<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  applyIndustryTemplate,
  loadExceptionAssignment,
  loadRuleCenterRiskRules,
  loadRuleConfig,
  loadRuleVersions,
  rollbackRuleVersion,
  runIndustryAlerts,
  updateExceptionAssignment,
  updateRuleCenterRiskRule,
  updateRuleConfig,
  type ExceptionAssignmentItem,
  type IndustryAlertResult,
  type RuleConfig,
  type RuleVersion,
} from '../services/rules'
import { loadSystemUsers, type SystemUser } from '../services/system'
import type { ProjectRiskRule } from '../services/projects'
import ModalPanel from '../components/ModalPanel.vue'
import { apiBase, useSession } from '../session'
import { exceptionTypeLabel, industryTemplateLabel } from '../utils/labels'
import { RULE_META, ruleName } from '../utils/riskRules'

const session = useSession()
const base = apiBase()
const rules = ref<ProjectRiskRule[]>([])
const config = ref<RuleConfig | null>(null)
const versions = ref<RuleVersion[]>([])
const assignmentModalOpen = ref(false)
const rulesModalOpen = ref(false)

function openAssignmentModal() {
  assignmentModalOpen.value = true
}

function closeAssignmentModal() {
  assignmentModalOpen.value = false
}

function openRulesModal() {
  rulesModalOpen.value = true
}

function closeRulesModal() {
  rulesModalOpen.value = false
}
const versionsRule = ref('')
const ruleHelp = ref<string | null>(null)

function toggleRuleHelp(ruleCode: string) {
  ruleHelp.value = ruleHelp.value === ruleCode ? null : ruleCode
}
const industry = ref('it_software')
const loading = ref(false)
const error = ref('')
const message = ref('')
const assignmentItems = ref<ExceptionAssignmentItem[]>([])
const assignmentForm = ref<Record<string, string>>({})
const assignmentUsers = ref<SystemUser[]>([])
const assignmentUserFallback = ref(false)
const assignmentError = ref('')
const alertResult = ref<IndustryAlertResult | null>(null)
const alertRunning = ref(false)

async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  error.value = ''
  try {
    const tenant = session.user.value.tenant_id
    const [ruleList, ruleConfig] = await Promise.all([
      loadRuleCenterRiskRules(base, session.token.value, tenant),
      loadRuleConfig(base, session.token.value, tenant),
    ])
    rules.value = ruleList.data
    config.value = ruleConfig.data
    industry.value = ruleConfig.data.industry_template
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '规则中心加载失败'
  } finally {
    loading.value = false
  }
}

async function saveRule(rule: ProjectRiskRule) {
  if (!session.user.value || !session.token.value) return
  try {
    await updateRuleCenterRiskRule(
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
    message.value = `风险规则「${ruleName(rule.rule_code)}」已保存并记录新版本。`
    if (versionsRule.value === rule.rule_code) await showVersions(rule.rule_code)
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '风险规则保存失败'
  }
}

async function showVersions(ruleCode: string) {
  if (!session.user.value || !session.token.value) return
  try {
    versions.value = (
      await loadRuleVersions(base, session.token.value, session.user.value.tenant_id, ruleCode)
    ).data
    versionsRule.value = ruleCode
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '规则版本加载失败'
  }
}

async function rollback(version: RuleVersion) {
  if (!session.user.value || !session.token.value) return
  try {
    await rollbackRuleVersion(
      base,
      session.token.value,
      session.user.value.tenant_id,
      version.rule_code,
      version.version_no,
    )
    message.value = `规则「${ruleName(version.rule_code)}」已回滚到版本 v${version.version_no}。`
    await load()
    await showVersions(version.rule_code)
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '规则回滚失败'
  }
}

async function saveConfig() {
  if (!session.user.value || !session.token.value || !config.value) return
  try {
    config.value = (
      await updateRuleConfig(base, session.token.value, session.user.value.tenant_id, {
        match_scan_window_days: config.value.match_scan_window_days,
        match_exact_window_days: config.value.match_exact_window_days,
        match_suggest_window_days: config.value.match_suggest_window_days,
      })
    ).data
    message.value = '匹配窗口已保存，后续回款匹配按新窗口执行。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '匹配窗口保存失败'
  }
}

async function applyTemplate() {
  if (!session.user.value || !session.token.value) return
  try {
    await applyIndustryTemplate(
      base,
      session.token.value,
      session.user.value.tenant_id,
      industry.value,
    )
    message.value = '行业模板已应用，风险规则阈值已按行业默认值更新。'
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '行业模板应用失败'
  }
}

async function loadAssignment() {
  if (!session.user.value || !session.token.value) return
  assignmentError.value = ''
  try {
    const tenant = session.user.value.tenant_id
    const items = (await loadExceptionAssignment(base, session.token.value, tenant)).data.items
    assignmentItems.value = items
    assignmentForm.value = Object.fromEntries(
      items.map((item) => [
        item.exception_type,
        item.owner_user_id === null ? '' : String(item.owner_user_id),
      ]),
    )
  } catch (cause) {
    assignmentError.value = cause instanceof Error ? cause.message : '异常分派配置加载失败'
  }
  try {
    assignmentUsers.value = (
      await loadSystemUsers(base, session.token.value, session.user.value.tenant_id, 1, 100)
    ).data.items.filter((user) => user.status === 'active')
  } catch {
    assignmentUserFallback.value = true
  }
}

async function saveAssignment() {
  if (!session.user.value || !session.token.value) return
  const assignments: Record<string, number | null> = {}
  for (const [type, value] of Object.entries(assignmentForm.value)) {
    const trimmed = value.trim()
    if (trimmed === '') {
      assignments[type] = null
      continue
    }
    const id = Number(trimmed)
    if (!Number.isInteger(id) || id <= 0) {
      message.value = `异常类型 ${exceptionTypeLabel(type)} 的处理人必须是有效的用户 ID`
      return
    }
    assignments[type] = id
  }
  try {
    const items = (
      await updateExceptionAssignment(
        base,
        session.token.value,
        session.user.value.tenant_id,
        assignments,
      )
    ).data.items
    assignmentItems.value = items
    message.value = '异常自动分派配置已保存。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '分派配置保存失败'
  }
}

async function runAlerts() {
  if (!session.user.value || !session.token.value) return
  alertRunning.value = true
  try {
    alertResult.value = (
      await runIndustryAlerts(base, session.token.value, session.user.value.tenant_id)
    ).data
    message.value = '行业预警已运行，幂等不重复生成异常。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '行业预警运行失败'
  } finally {
    alertRunning.value = false
  }
}

function versionSourceLabel(source: string) {
  if (source === 'baseline') return '基线'
  if (source === 'manual') return '手动修改'
  if (source === 'rollback') return '回滚'
  if (source === 'template') return '行业模板'
  return source
}

onMounted(() => {
  load()
  loadAssignment()
})
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">规则中心</p>
        <h2>规则配置与版本管理</h2>
        <p class="lead">维护项目风险规则、匹配窗口和行业模板，规则变更记录版本并支持回滚。</p>
      </div>
      <span v-if="config" class="meta"
        >当前行业模板：{{ industryTemplateLabel(config.industry_template) }}</span
      >
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message" class="feedback-text">{{ message }}</p>

    <div class="page-toolbar">
      <button class="ghost-button" type="button" @click="openAssignmentModal">异常自动分派</button>
      <button class="ghost-button" type="button" @click="openRulesModal">项目风险规则</button>
    </div>

    <article class="panel workflow-panel">
      <h3>行业模型配置</h3>
      <label
        >行业模板<select v-model="industry">
          <option value="it_software">IT 软件与信息服务</option>
        </select></label
      >
      <button
        v-permission="'project:rule'"
        class="primary-button"
        type="button"
        @click="applyTemplate"
      >
        应用行业模板
      </button>
      <p class="meta import-hint">
        应用模板将把该行业的默认阈值和扣分写入全部风险规则，并逐条记录来源为「行业模板」的新版本。
      </p>
      <h3>匹配窗口</h3>
      <div v-if="config" class="filter-bar">
        <label
          >扫描窗口（天）<input
            v-model.number="config.match_scan_window_days"
            type="number"
            min="1"
            max="90"
        /></label>
        <label
          >精确匹配窗口（天）<input
            v-model.number="config.match_exact_window_days"
            type="number"
            min="1"
            max="90"
        /></label>
        <label
          >推荐匹配窗口（天）<input
            v-model.number="config.match_suggest_window_days"
            type="number"
            min="1"
            max="90"
        /></label>
        <button
          v-permission="'project:rule'"
          class="small-primary-button"
          type="button"
          @click="saveConfig"
        >
          保存窗口
        </button>
      </div>
      <p class="meta import-hint">
        扫描窗口限定参与匹配的应收日期范围（默认 30 天）；精确窗口默认 7 天；推荐/拆分/合并窗口默认
        14 天；合并流水的同日窗口与精确窗口一致。
      </p>
    </article>

    <ModalPanel v-if="assignmentModalOpen" title="异常自动分派" @close="closeAssignmentModal">
      <p class="meta">新异常按类型自动分派给处理人，留空表示不分派</p>
      <p v-if="assignmentError" class="error-banner">{{ assignmentError }}</p>
      <div v-else-if="assignmentItems.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>异常类型</th>
              <th>处理人</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in assignmentItems" :key="item.exception_type">
              <td>{{ exceptionTypeLabel(item.exception_type) }}</td>
              <td>
                <select
                  v-if="!assignmentUserFallback"
                  v-model="assignmentForm[item.exception_type]"
                >
                  <option value="">不分派</option>
                  <option v-for="user in assignmentUsers" :key="user.id" :value="String(user.id)">
                    {{ user.display_name }}（{{ user.login_name }}）
                  </option>
                </select>
                <input
                  v-else
                  v-model="assignmentForm[item.exception_type]"
                  placeholder="用户 ID，留空不分派"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">分派配置加载中...</p>
      <div class="action-group">
        <button
          v-permission="'exception:assign'"
          class="small-primary-button"
          :disabled="!assignmentItems.length"
          type="button"
          @click="saveAssignment"
        >
          保存分派配置
        </button>
      </div>
      <template #footer>
        <button class="primary-button" type="button" @click="closeAssignmentModal">关闭</button>
      </template>
    </ModalPanel>

    <article class="panel workflow-panel">
      <h3>行业预警</h3>
      <p class="meta import-hint">
        按行业规则扫描并自动生成异常：验收款逾期、质保金逾期、客户回款风险、工资类支出缺口、项目现金流风险；幂等，重复运行不重复生成。
      </p>
      <div class="action-group">
        <button
          v-permission="'project:rule'"
          class="primary-button"
          :disabled="alertRunning"
          type="button"
          @click="runAlerts"
        >
          {{ alertRunning ? '运行中...' : '立即运行' }}
        </button>
      </div>
      <div v-if="alertResult" class="import-summary">
        <span>验收款逾期 {{ alertResult.acceptance_overdue }}</span
        ><span>质保金逾期 {{ alertResult.warranty_overdue }}</span
        ><span>客户回款风险 {{ alertResult.customer_risk }}</span
        ><span>工资类支出缺口 {{ alertResult.payroll_gap }}</span
        ><span>项目现金流风险 {{ alertResult.project_cashflow_risk }}</span>
      </div>
    </article>

    <ModalPanel v-if="rulesModalOpen" title="项目风险规则" @close="closeRulesModal">
      <p class="meta">修改自动记录版本，可随时回滚 <span v-if="loading">加载中...</span></p>
      <div v-if="rules.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>规则</th>
              <th>阈值</th>
              <th>扣分</th>
              <th>扣分上限</th>
              <th>启用</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="rule in rules" :key="rule.rule_code">
              <tr>
                <td>
                  <span class="rule-identity"
                    ><strong>{{ ruleName(rule.rule_code) }}</strong
                    ><button
                      v-if="RULE_META[rule.rule_code]"
                      class="rule-help-icon"
                      type="button"
                      :aria-expanded="ruleHelp === rule.rule_code"
                      :aria-label="`查看${ruleName(rule.rule_code)}的说明与用法`"
                      @click="toggleRuleHelp(rule.rule_code)"
                    >
                      ?
                    </button></span
                  >
                </td>
                <td><input v-model="rule.threshold" type="number" step="0.01" /></td>
                <td><input v-model="rule.penalty" type="number" step="1" /></td>
                <td><input v-model="rule.max_penalty" type="number" step="1" /></td>
                <td><input v-model="rule.enabled" type="checkbox" /></td>
                <td>
                  <div class="action-group">
                    <button
                      v-permission="'project:rule'"
                      class="text-button"
                      type="button"
                      @click="saveRule(rule)"
                    >
                      保存</button
                    ><button
                      class="text-button"
                      type="button"
                      @click="showVersions(rule.rule_code)"
                    >
                      版本
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="ruleHelp === rule.rule_code">
                <td colspan="6">
                  <p class="rule-help">
                    {{ RULE_META[rule.rule_code]?.description }}（字段标识：{{ rule.rule_code }}）
                  </p>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无风险规则。</p>

      <div v-if="versionsRule" class="detail-section">
        <div class="section-heading">
          <div>
            <h3>版本历史：{{ ruleName(versionsRule) }}（{{ versionsRule }}）</h3>
            <span class="meta">回滚会把所选版本写回当前规则并记录新版本</span>
          </div>
          <button class="ghost-button" type="button" @click="versionsRule = ''">收起</button>
        </div>
        <div v-if="versions.length" class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>版本</th>
                <th>阈值</th>
                <th>扣分</th>
                <th>扣分上限</th>
                <th>启用</th>
                <th>来源</th>
                <th>备注</th>
                <th>时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="version in versions" :key="version.id">
                <td>v{{ version.version_no }}</td>
                <td>{{ version.threshold }}</td>
                <td>{{ version.penalty }}</td>
                <td>{{ version.max_penalty ?? '-' }}</td>
                <td>{{ version.enabled ? '是' : '否' }}</td>
                <td>
                  <span class="pill">{{ versionSourceLabel(version.change_source) }}</span>
                </td>
                <td>{{ version.remark || '-' }}</td>
                <td>{{ version.created_at }}</td>
                <td>
                  <button
                    v-permission="'project:rule'"
                    class="text-button"
                    type="button"
                    @click="rollback(version)"
                  >
                    回滚到此版本
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-state">该规则还没有版本记录，保存一次后自动生成基线版本。</p>
      </div>
      <template #footer>
        <button class="primary-button" type="button" @click="closeRulesModal">关闭</button>
      </template>
    </ModalPanel>
  </section>
</template>
