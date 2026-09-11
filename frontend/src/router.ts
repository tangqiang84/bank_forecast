import { createRouter, createWebHashHistory } from 'vue-router'
import { apiBase, useSession } from './session'
import { loadCurrentUser } from './services/auth'
import LoginView from './views/LoginView.vue'
import WorkspaceLayout from './views/WorkspaceLayout.vue'
import DashboardView from './views/DashboardView.vue'
import TransactionsView from './views/TransactionsView.vue'
import ImportJobView from './views/ImportJobView.vue'
import EntityListView from './views/EntityListView.vue'
import EntityDetailView from './views/EntityDetailView.vue'
import ReconciliationView from './views/ReconciliationView.vue'
import ForecastView from './views/ForecastView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { guest: true } },
    {
      path: '/', component: WorkspaceLayout, meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: DashboardView },
        { path: 'transactions', component: TransactionsView },
        { path: 'imports/:jobId', component: ImportJobView },
        { path: 'accounts', component: EntityListView, props: { kind: 'accounts' } },
        { path: 'receivables', component: EntityListView, props: { kind: 'receivables' } },
        { path: 'projects', component: EntityListView, props: { kind: 'projects' } },
        { path: 'matching', component: EntityListView, props: { kind: 'matching' } },
        { path: 'exceptions', component: EntityListView, props: { kind: 'exceptions' } },
        { path: 'reconciliation', component: ReconciliationView },
        { path: 'reconciliation/jobs/:jobId', component: ReconciliationView },
        { path: 'reports', component: EntityListView, props: { kind: 'reports' } },
        { path: 'forecast', component: ForecastView },
        { path: 'forecast/jobs/:jobId', component: ForecastView },
        { path: 'accounts/:id', component: EntityDetailView, props: { kind: 'account' } },
        { path: 'transactions/:id', component: EntityDetailView, props: { kind: 'transaction' } },
        { path: 'contracts/:id', component: EntityDetailView, props: { kind: 'contract' } },
        { path: 'projects/:id', component: EntityDetailView, props: { kind: 'project' } },
        { path: 'matching/:id', component: EntityDetailView, props: { kind: 'match' } },
        { path: 'exceptions/:id', component: EntityDetailView, props: { kind: 'exception' } },
        { path: 'reports/:id', component: EntityDetailView, props: { kind: 'report' } },
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
