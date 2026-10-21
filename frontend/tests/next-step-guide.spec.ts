import { expect, test, type Page } from '@playwright/test'
import { gotoNav } from './helpers/nav'

// 下一步引导条（P6-6）隔离 E2E：mock backend，仅验证前端规则与跳转，不证明真实服务可用

const FULL_PERMISSIONS = [
  'dashboard:view',
  'account:view',
  'transaction:view',
  'transaction:import',
  'transaction:export',
  'import:view',
  'contract:view',
  'project:view',
  'project:rule',
  'matching:view',
  'matching:run',
  'matching:confirm',
  'exception:view',
  'exception:assign',
  'exception:handle',
  'reconciliation:view',
  'reconciliation:run',
  'report:view',
  'report:generate',
  'report:download',
  'template:manage',
  'system:manage',
]

type StatsOverride = {
  my_todo_count: number
  unassigned_count: number
  review_pending_count: number
}

function zeroStats(): StatsOverride {
  return { my_todo_count: 0, unassigned_count: 0, review_pending_count: 0 }
}

function ok(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 0, message: 'ok', data, trace_id: 'e2e' }),
  }
}

function emptyPage() {
  return ok({ items: [], page: 1, page_size: 20, total: 0 })
}

// 与页面本地时钟同月的一条对账差异（created_at 取当月，保证「本月已对账」判断与运行日期无关）
function thisMonthReconciliationItem() {
  return {
    id: 9001,
    exception_no: 'EX-RECON-1',
    exception_type: 'bank_unrecorded',
    source_type: 'bank_transaction',
    source_id: 1,
    title: '对账差异',
    description: '',
    status: 'new',
    severity: 'low',
    transaction_no: null,
    transaction_date: null,
    bank_amount: null,
    record_no: null,
    record_date: null,
    finance_amount: null,
    created_at: `${new Date().toISOString().slice(0, 7)}-05 10:00:00`,
  }
}

async function mockBase(page: Page, permissions: string[]) {
  await page.route('**/api/v1/auth/login', async (route) =>
    route.fulfill(
      ok({
        access_token: 'e2e-token',
        user: {
          id: 1,
          tenant_id: 1,
          login_name: 'e2e',
          display_name: '端到端用户',
          roles: ['CFO'],
          permissions,
        },
      }),
    ),
  )
  await page.route('**/api/v1/auth/me', async (route) =>
    route.fulfill(
      ok({
        id: 1,
        tenant_id: 1,
        login_name: 'e2e',
        display_name: '端到端用户',
        roles: ['CFO'],
        permissions,
      }),
    ),
  )
  await page.route('**/api/v1/dashboard/overview', async (route) =>
    route.fulfill(
      ok({
        total_balance: '0.00',
        yesterday_net_inflow: '0.00',
        pending_exceptions: 0,
        idle_accounts: 0,
        match_rate: 0,
        last_sync_at: '2026-10-21 08:00:00',
        top_receivables: [],
        key_risks: [],
        recent_import_jobs: [],
      }),
    ),
  )
  await page.route('**/api/v1/dashboard/trends?**', async (route) =>
    route.fulfill(ok({ days: 30, start: '2026-09-22', end: '2026-10-21', series: [] })),
  )
  await page.route('**/api/v1/metrics/pilot-acceptance', async (route) =>
    route.fulfill(
      ok({ as_of: '2026-10-21', last_snapshot_date: null, indicators: [], amounts: [] }),
    ),
  )
  await page.route('**/api/v1/bank-accounts?**', async (route) => route.fulfill(emptyPage()))
  await page.route('**/api/v1/bank-transactions?**', async (route) => route.fulfill(emptyPage()))
  await page.route('**/api/v1/finance-records?**', async (route) => route.fulfill(emptyPage()))
  await page.route('**/api/v1/matching/exceptions?**', async (route) => route.fulfill(emptyPage()))
}

async function mockStats(page: Page, stats: StatsOverride) {
  await page.route('**/api/v1/matching/exceptions/stats', async (route) =>
    route.fulfill(
      ok({
        active_count: 0,
        unassigned_count: stats.unassigned_count,
        my_todo_count: stats.my_todo_count,
        pending_close_count: 0,
        overdue_count: 0,
        closed_count: 0,
        false_positive_count: 0,
        total_count: 0,
        closure_rate: 0,
        avg_resolution_hours: 0,
        today_new_count: 0,
        review_pending_count: stats.review_pending_count,
        large_amount_count: 0,
        large_amount_threshold: 100000,
        by_type: [],
        by_owner: [],
      }),
    ),
  )
}

async function mockReconciliationResults(page: Page, items: unknown[]) {
  await page.route('**/api/v1/reconciliation/results**', async (route) =>
    route.fulfill(ok({ items, page: 1, page_size: 20, total: items.length })),
  )
}

async function mockReports(page: Page, items: unknown[]) {
  await page.route('**/api/v1/reports?**', async (route) =>
    route.fulfill(ok({ items, page: 1, page_size: 20, total: items.length })),
  )
}

async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('登录名').fill('e2e-login')
  await page.getByLabel('密码').fill('e2e-password')
  await page.getByRole('button', { name: '登录' }).click()
}

test('流水页有待办时显示「去处理」引导并可点击跳转', async ({ page }) => {
  await mockBase(page, FULL_PERMISSIONS)
  await mockStats(page, { my_todo_count: 2, unassigned_count: 2, review_pending_count: 1 })
  await mockReconciliationResults(page, [thisMonthReconciliationItem()])
  await mockReports(page, [])
  await login(page)

  await gotoNav(page, '流水查询')
  await expect(page).toHaveURL(/#\/transactions$/)
  const guide = page.locator('.next-step-guide')
  await expect(guide).toContainText('流水已就绪，还有 5 笔回款待核销')
  await guide.getByRole('link', { name: '去处理 →' }).click()
  await expect(page).toHaveURL(/#\/exceptions$/)
  await expect(page.getByRole('heading', { name: '待办处理' })).toBeVisible()
})

test('流水页待办为 0 时不显示「去处理」引导', async ({ page }) => {
  await mockBase(page, FULL_PERMISSIONS)
  await mockStats(page, zeroStats())
  // 本月已有对账差异（同月 created_at），月末规则同样不命中，保证与运行日期无关
  await mockReconciliationResults(page, [thisMonthReconciliationItem()])
  await mockReports(page, [])
  await login(page)

  await gotoNav(page, '流水查询')
  await expect(page.getByRole('heading', { name: '流水列表' })).toBeVisible()
  await expect(page.locator('.next-step-guide')).toHaveCount(0)
})

test('无 exception/matching 权限的用户不显示「去处理」引导', async ({ page }) => {
  await mockBase(page, ['transaction:view'])
  await mockStats(page, { my_todo_count: 7, unassigned_count: 0, review_pending_count: 0 })
  await mockReconciliationResults(page, [thisMonthReconciliationItem()])
  await login(page)

  // 仅 transaction:view：登录直接落地流水查询页
  await expect(page).toHaveURL(/#\/transactions$/)
  await expect(page.getByRole('heading', { name: '流水列表' })).toBeVisible()
  await expect(page.locator('.next-step-guide')).toHaveCount(0)
  await expect(page.getByText('笔回款待核销')).toHaveCount(0)
})

test('对账页本月无对账任务时显示「运行对账」引导', async ({ page }) => {
  await mockBase(page, FULL_PERMISSIONS)
  await mockStats(page, zeroStats())
  await mockReconciliationResults(page, [])
  await mockReports(page, [])
  await login(page)

  await gotoNav(page, '财务对账')
  await expect(page).toHaveURL(/#\/reconciliation$/)
  const guide = page.locator('.next-step-guide')
  await expect(guide).toContainText('本月还没运行对账，点击右上角「运行对账」开始')
  await expect(guide.getByRole('link')).toHaveCount(0)
})

test('待办清零且月末时工作台显示「去对账」引导', async ({ page }) => {
  await page.clock.install({ time: new Date(2026, 9, 28, 10, 0, 0) })
  await mockBase(page, FULL_PERMISSIONS)
  await mockStats(page, zeroStats())
  await mockReconciliationResults(page, [])
  await mockReports(page, [])
  await login(page)

  await gotoNav(page, '待办处理')
  await expect(page).toHaveURL(/#\/exceptions$/)
  const guide = page.locator('.next-step-guide')
  await expect(guide).toContainText('今日待办已清零，本月财务对账还没做')
  await guide.getByRole('link', { name: '去对账 →' }).click()
  await expect(page).toHaveURL(/#\/reconciliation$/)
  await expect(page.getByRole('heading', { name: '银行账 / 财务账对账' })).toBeVisible()
})

test('待办清零、本月已对账且月末时显示「去生成」月报引导', async ({ page }) => {
  await page.clock.install({ time: new Date(2026, 9, 28, 10, 0, 0) })
  await mockBase(page, FULL_PERMISSIONS)
  await mockStats(page, zeroStats())
  await mockReconciliationResults(page, [
    { ...thisMonthReconciliationItem(), created_at: '2026-10-05 10:00:00' },
  ])
  await mockReports(page, [])
  await login(page)

  await gotoNav(page, '待办处理')
  await expect(page).toHaveURL(/#\/exceptions$/)
  const guide = page.locator('.next-step-guide')
  await expect(guide).toContainText('本月月报还没生成')
  await guide.getByRole('link', { name: '去生成 →' }).click()
  await expect(page).toHaveURL(/#\/reports$/)
  await expect(page.getByRole('heading', { name: '资金经营报告' })).toBeVisible()
})
