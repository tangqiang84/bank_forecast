import { createRouter, createWebHashHistory } from 'vue-router'
import { apiBase, useSession } from './session'
import { loadCurrentUser } from './services/auth'
import { hasPermission } from './utils/permission'
import { landingPath } from './utils/navigation'
import LoginView from './views/LoginView.vue'
import WorkspaceLayout from './views/WorkspaceLayout.vue'
import ForbiddenView from './views/ForbiddenView.vue'
import DashboardView from './views/DashboardView.vue'
import TransactionsView from './views/TransactionsView.vue'
import ImportJobView from './views/ImportJobView.vue'
import EntityDetailView from './views/EntityDetailView.vue'
import ReconciliationView from './views/ReconciliationView.vue'
import ForecastView from './views/ForecastView.vue'
import AccountsView from './views/AccountsView.vue'
import AccountAnalysisView from './views/AccountAnalysisView.vue'
import ReceivablesView from './views/ReceivablesView.vue'
import ProjectsView from './views/ProjectsView.vue'
import ExceptionsView from './views/ExceptionsView.vue'
import ReportsView from './views/ReportsView.vue'
import RulesView from './views/RulesView.vue'
import TemplatesView from './views/TemplatesView.vue'
import SystemView from './views/SystemView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  },
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { guest: true } },
    {
      path: '/',
      name: 'workspace',
      component: WorkspaceLayout,
      meta: { requiresAuth: true },
      children: [
        // 根路径占位：实际落地由路由守卫 landingPath 按角色决定
        { path: '', name: 'workspace-root', component: ForbiddenView },
        { path: '403', name: 'forbidden', component: ForbiddenView },
        {
          path: 'dashboard',
          name: 'dashboard',
          component: DashboardView,
          meta: { permission: 'dashboard:view' },
        },
        {
          path: 'transactions',
          name: 'transactions',
          component: TransactionsView,
          meta: { permission: 'transaction:view' },
        },
        {
          path: 'imports/:jobId',
          name: 'import-job',
          component: ImportJobView,
          meta: { permission: 'import:view' },
        },
        {
          path: 'accounts',
          name: 'accounts',
          component: AccountsView,
          meta: { permission: 'account:view' },
        },
        {
          path: 'accounts/analysis',
          name: 'account-analysis',
          component: AccountAnalysisView,
          meta: { permission: 'account:view' },
        },
        {
          path: 'receivables',
          name: 'receivables',
          component: ReceivablesView,
          meta: { permission: 'contract:view' },
        },
        {
          path: 'projects',
          name: 'projects',
          component: ProjectsView,
          meta: { permission: 'project:view' },
        },
        {
          path: 'matching',
          name: 'matching',
          redirect: { name: 'exceptions' },
        },
        {
          path: 'matching/:id',
          name: 'matching-detail',
          redirect: (to) => ({ name: 'exceptions', query: { match_result: String(to.params.id) } }),
        },
        {
          path: 'exceptions',
          name: 'exceptions',
          component: ExceptionsView,
          meta: { permission: ['exception:view', 'matching:view'] },
        },
        {
          path: 'exceptions/:id',
          name: 'exception-detail',
          redirect: (to) => ({ name: 'exceptions', query: { exception: String(to.params.id) } }),
        },
        {
          path: 'reconciliation',
          name: 'reconciliation',
          component: ReconciliationView,
          meta: { permission: 'reconciliation:view' },
        },
        {
          path: 'reconciliation/jobs/:jobId',
          name: 'reconciliation-job',
          component: ReconciliationView,
          meta: { permission: 'reconciliation:view' },
        },
        {
          path: 'rules',
          name: 'rules',
          component: RulesView,
          meta: { permission: 'project:rule' },
        },
        {
          path: 'templates',
          name: 'templates',
          component: TemplatesView,
          meta: { permission: 'template:manage' },
        },
        {
          path: 'system',
          name: 'system',
          component: SystemView,
          meta: { permission: 'system:manage' },
        },
        {
          path: 'reports',
          name: 'reports',
          component: ReportsView,
          meta: { permission: 'report:view' },
        },
        {
          path: 'forecast',
          name: 'forecast',
          component: ForecastView,
          meta: { permission: 'forecast:view' },
        },
        {
          path: 'forecast/jobs/:jobId',
          name: 'forecast-job',
          component: ForecastView,
          meta: { permission: 'forecast:view' },
        },
        {
          path: 'accounts/:id',
          name: 'account-detail',
          component: EntityDetailView,
          props: { kind: 'account' },
          meta: { permission: 'account:view' },
        },
        {
          path: 'transactions/:id',
          name: 'transaction-detail',
          component: EntityDetailView,
          props: { kind: 'transaction' },
          meta: { permission: 'transaction:view' },
        },
        {
          path: 'contracts/:id',
          name: 'contract-detail',
          component: EntityDetailView,
          props: { kind: 'contract' },
          meta: { permission: 'contract:view' },
        },
        {
          path: 'projects/:id',
          name: 'project-detail',
          component: EntityDetailView,
          props: { kind: 'project' },
          meta: { permission: 'project:view' },
        },
        {
          path: 'reports/:id',
          name: 'report-detail',
          component: EntityDetailView,
          props: { kind: 'report' },
          meta: { permission: 'report:view' },
        },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const session = useSession()
  if (!session.user.value && session.token.value) {
    try {
      session.user.value = await loadCurrentUser(apiBase(), session.token.value)
    } catch {
      session.signOut()
    }
  }
  if (to.meta.requiresAuth && !session.loggedIn.value) return '/login'
  if (to.meta.guest && session.loggedIn.value) return landingPath(session.user.value)
  // 角色默认落地页（P4-19）：根路径在用户加载完成后按角色跳转
  if (to.name === 'workspace-root') return landingPath(session.user.value)
  const required = to.meta.permission as string | string[] | undefined
  if (required && session.loggedIn.value) {
    const codes = Array.isArray(required) ? required : [required]
    if (!codes.some((code) => hasPermission(session.user.value, code))) {
      return { name: 'forbidden' }
    }
  }
  return true
})

export default router
