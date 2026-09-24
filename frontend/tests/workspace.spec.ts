import { expect, test, type Page } from '@playwright/test'

async function mockWorkspaceApi(page: Page) {
  await page.route('**/api/v1/auth/login', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: {
          access_token: 'e2e-token',
          user: {
            id: 1,
            tenant_id: 1,
            login_name: 'finance01',
            display_name: '财务负责人',
            roles: ['CFO'],
            permissions: [],
          },
        },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/dashboard/overview', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: {
          total_balance: '100.00',
          yesterday_net_flow: '10.00',
          receivable_total: '80.00',
          overdue_receivable: '20.00',
          open_exception_count: 0,
          match_rate: '1.00',
          key_risks: [],
          recent_import_jobs: [],
        },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/bank-accounts?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: { items: [], page: 1, page_size: 20, total: 0 },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/contracts/receivables**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: { items: [], page: 1, page_size: 20, total: 0 },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/projects/risk-rules', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: [], trace_id: 'e2e' }),
    }),
  )
  await page.route('**/api/v1/projects?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: { items: [], page: 1, page_size: 20, total: 0 },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/matching/results?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: { items: [], page: 1, page_size: 20, total: 0 },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/matching/exceptions?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: { items: [], page: 1, page_size: 20, total: 0 },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/reports?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: { items: [], page: 1, page_size: 20, total: 0 },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/reconciliation/results**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: { items: [], page: 1, page_size: 20, total: 0 },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/forecast/cashflow/latest', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: {
          job: null,
          results: [],
          evaluation: { evaluated_points: 0, mae: '0', rmse: '0', mean_deviation: '0' },
        },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/forecast/models', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: [], trace_id: 'e2e' }),
    }),
  )
}

async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('密码').fill('e2e-password')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/#\/dashboard$/)
}

test('对账和现金预测拥有独立业务页面', async ({ page }) => {
  await mockWorkspaceApi(page)
  await login(page)

  await page.getByRole('link', { name: '财务对账' }).click()
  await expect(page.getByRole('heading', { name: '银行账 / 财务账对账' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '差异结果' })).toBeVisible()

  await page.getByRole('link', { name: '现金预测' }).click()
  await expect(page.getByRole('heading', { name: '现金流预测工作台' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '预测任务详情' })).toBeVisible()
})

test('恢复核心业务模块的原有操作入口', async ({ page }) => {
  await mockWorkspaceApi(page)
  await login(page)

  await page.getByRole('link', { name: '银行账户' }).click()
  await expect(page.getByRole('heading', { name: '账户盘点与流水导入' })).toBeVisible()
  await expect(page.getByRole('button', { name: '预览导入' })).toBeVisible()

  await page.getByRole('link', { name: '合同应收' }).click()
  await expect(page.getByRole('heading', { name: '合同回款计划' })).toBeVisible()
  await expect(page.getByRole('button', { name: '导入合同应收' })).toBeVisible()

  await page.getByRole('link', { name: '项目资金' }).click()
  await expect(page.getByRole('heading', { name: '项目资金与风险' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '项目风险规则' })).toBeVisible()

  await page.getByRole('link', { name: '匹配结果' }).click()
  await expect(page.getByRole('heading', { name: '回款匹配工作台' })).toBeVisible()
  await expect(page.getByRole('button', { name: '运行回款匹配' })).toBeVisible()

  await page.getByRole('link', { name: '异常事项' }).click()
  await expect(page.getByRole('heading', { name: '异常处理工作台' })).toBeVisible()

  await page.getByRole('link', { name: '报表中心' }).click()
  await expect(page.getByRole('heading', { name: '资金经营报告' })).toBeVisible()
  await expect(page.getByRole('button', { name: '生成报表' })).toBeVisible()
})
