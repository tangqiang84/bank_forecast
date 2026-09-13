import { createRouter, createWebHashHistory } from 'vue-router'
import { apiBase, useSession } from './session'
import { loadCurrentUser } from './services/auth'
import LoginView from './views/LoginView.vue'
import WorkspaceLayout from './views/WorkspaceLayout.vue'
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
      path: '/', name: 'workspace', component: WorkspaceLayout, meta: { requiresAuth: true },
      children: [
        { path: '', name: 'workspace-root', redirect: { name: 'dashboard' } },
        { path: 'dashboard', name: 'dashboard', component: DashboardView },
        { path: 'transactions', name: 'transactions', component: TransactionsView },
        { path: 'imports/:jobId', name: 'import-job', component: ImportJobView },
        { path: 'accounts', name: 'accounts', component: AccountsView },
        { path: 'receivables', name: 'receivables', component: ReceivablesView },
        { path: 'projects', name: 'projects', component: ProjectsView },
        { path: 'matching', name: 'matching', component: MatchingView },
        { path: 'exceptions', name: 'exceptions', component: ExceptionsView },
        { path: 'reconciliation', name: 'reconciliation', component: ReconciliationView },
        { path: 'reconciliation/jobs/:jobId', name: 'reconciliation-job', component: ReconciliationView },
        { path: 'reports', name: 'reports', component: ReportsView },
        { path: 'forecast', name: 'forecast', component: ForecastView },
        { path: 'forecast/jobs/:jobId', name: 'forecast-job', component: ForecastView },
        { path: 'accounts/:id', name: 'account-detail', component: EntityDetailView, props: { kind: 'account' } },
        { path: 'transactions/:id', name: 'transaction-detail', component: EntityDetailView, props: { kind: 'transaction' } },
        { path: 'contracts/:id', name: 'contract-detail', component: EntityDetailView, props: { kind: 'contract' } },
        { path: 'projects/:id', name: 'project-detail', component: EntityDetailView, props: { kind: 'project' } },
        { path: 'matching/:id', name: 'matching-detail', component: EntityDetailView, props: { kind: 'match' } },
        { path: 'exceptions/:id', name: 'exception-detail', component: EntityDetailView, props: { kind: 'exception' } },
        { path: 'reports/:id', name: 'report-detail', component: EntityDetailView, props: { kind: 'report' } },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const session = useSession()
  if (!session.user.value && session.token.value) {
    try { session.user.value = await loadCurrentUser(apiBase(), session.token.value) } catch { session.signOut() }
  }
  if (to.meta.requiresAuth && !session.loggedIn.value) return '/login'
  if (to.meta.guest && session.loggedIn.value) return '/dashboard'
  return true
})

export default router
