import { expect, test, type Page } from '@playwright/test'

const EMPTY_PAGE = { items: [], page: 1, page_size: 20, total: 0 }

function ok(data: unknown) {
  return JSON.stringify({ code: 0, message: 'ok', data, trace_id: 'e2e' })
}

async function mockDashboard(page: Page, permissions: string[]) {
  await page.route('**/api/v1/auth/login', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        access_token: 'e2e-token',
        user: {
          id: 1,
          tenant_id: 1,
          login_name: 'finance01',
          display_name: '财务负责人',
          roles: [],
          permissions,
        },
      }),
    }),
  )
  await page.route('**/api/v1/dashboard/overview', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        total_balance: '100.00',
        yesterday_net_flow: '10.00',
        receivable_total: '80.00',
        overdue_receivable: '20.00',
        open_exception_count: 0,
        match_rate: '1.00',
        key_risks: [],
        recent_import_jobs: [],
      }),
    }),
  )
  await page.route('**/api/v1/bank-accounts?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(EMPTY_PAGE) }),
  )
  await page.route('**/api/v1/metrics/pilot-acceptance', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ as_of: '2026-10-21', indicators: [], amounts: [], last_snapshot_date: null }),
    }),
  )
  await page.route('**/api/v1/dashboard/trends?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ days: 30, start: '2026-09-22', end: '2026-10-21', series: [] }),
    }),
  )
}

async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('密码').fill('e2e-password')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/#\/dashboard$/)
}

function quickActions(page: Page) {
  return page.locator('section.panel').filter({
    has: page.getByRole('heading', { name: '快捷操作', exact: true }),
  })
}

test('驾驶舱快捷操作区一键匹配并展示结果与去处理链接', async ({ page }) => {
  let runRequested = false

  await mockDashboard(page, ['dashboard:view', 'matching:run'])
  await page.route('**/api/v1/matching/receivables/run', async (route) => {
    runRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ auto_matched_count: 2, pending_review_count: 1 }),
    })
  })

  await login(page)
  const actions = quickActions(page)
  await expect(actions).toBeVisible()
  await expect(actions.getByRole('button', { name: '一键匹配' })).toBeVisible()
  await expect(actions.getByRole('link', { name: '导入数据' })).toHaveCount(0)
  await expect(actions.getByRole('link', { name: '生成报表' })).toHaveCount(0)

  await actions.getByRole('button', { name: '一键匹配' }).click()
  await expect(actions.getByText(/匹配完成：自动核销\s*2\s*笔，待人工\s*1\s*笔。/)).toBeVisible()
  expect(runRequested).toBe(true)
  await expect(actions.getByRole('link', { name: '去处理 →' })).toHaveAttribute(
    'href',
    '#/exceptions',
  )
})

test('一键匹配失败时展示中文错误提示', async ({ page }) => {
  await mockDashboard(page, ['dashboard:view', 'matching:run'])
  await page.route('**/api/v1/matching/receivables/run', async (route) =>
    route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 50001,
        message: '匹配服务暂时不可用，请稍后重试',
        trace_id: 'e2e',
      }),
    }),
  )

  await login(page)
  const actions = quickActions(page)
  await actions.getByRole('button', { name: '一键匹配' }).click()
  await expect(actions.getByText('匹配服务暂时不可用，请稍后重试')).toBeVisible()
  await expect(actions.getByText(/匹配完成/)).toHaveCount(0)
  await expect(actions.getByRole('button', { name: '一键匹配' })).toBeEnabled()
})

test('导入数据与生成报表入口按权限展示并链接到目标页', async ({ page }) => {
  await mockDashboard(page, ['dashboard:view', 'transaction:import', 'report:generate'])

  await login(page)
  const actions = quickActions(page)
  await expect(actions.getByRole('link', { name: '导入数据' })).toHaveAttribute('href', '#/imports')
  await expect(actions.getByRole('link', { name: '生成报表' })).toHaveAttribute('href', '#/reports')
  await expect(actions.getByRole('button', { name: '一键匹配' })).toHaveCount(0)
})

test('无快捷操作相关权限时整个区块不渲染', async ({ page }) => {
  await mockDashboard(page, ['dashboard:view'])

  await login(page)
  await expect(page.getByRole('heading', { name: '今天的资金状态' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '快捷操作' })).toHaveCount(0)
  await expect(quickActions(page)).toHaveCount(0)
})
