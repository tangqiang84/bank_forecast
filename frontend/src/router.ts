import { createRouter, createWebHashHistory } from 'vue-router'
import { apiBase, useSession } from './session'
import { loadCurrentUser } from './services/auth'
import { hasPermission } from './utils/permission'
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
import ReceivablesView from './views/ReceivablesView.vue'
import ProjectsView from './views/ProjectsView.vue'
import MatchingView from './views/MatchingView.vue'
import ExceptionsView from './views/ExceptionsView.vue'
import ReportsView from './views/ReportsView.vue'

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
        { path: '', name: 'workspace-root', redirect: { name: 'dashboard' } },
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
          component: MatchingView,
          meta: { permission: 'matching:view' },
        },
        {
          path: 'exceptions',
          name: 'exceptions',
          component: ExceptionsView,
          meta: { permission: 'exception:view' },
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
          path: 'matching/:id',
          name: 'matching-detail',
          component: EntityDetailView,
          props: { kind: 'match' },
          meta: { permission: 'matching:view' },
        },
        {
          path: 'exceptions/:id',
          name: 'exception-detail',
          component: EntityDetailView,
          props: { kind: 'exception' },
          meta: { permission: 'exception:view' },
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
  if (to.meta.guest && session.loggedIn.value) return '/dashboard'
  const required = to.meta.permission as string | undefined
  if (required && session.loggedIn.value && !hasPermission(session.user.value, required)) {
    return { name: 'forbidden' }
  }
  return true
})

export default router
