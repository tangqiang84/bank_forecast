import { expect, test, type Page } from '@playwright/test'

async function mockWorkspaceApi(page: Page) {
  await page.route('**/api/v1/auth/login', async (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 0, message: 'ok', data: { access_token: 'e2e-token', user: { id: 1, tenant_id: 1, login_name: 'finance01', display_name: '财务负责人', roles: ['CFO'], permissions: [] } }, trace_id: 'e2e' }),
  }))
  await page.route('**/api/v1/dashboard/overview', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: { total_balance: '100.00', yesterday_net_flow: '10.00', receivable_total: '80.00', overdue_receivable: '20.00', open_exception_count: 0, match_rate: '1.00', key_risks: [], recent_import_jobs: [] }, trace_id: 'e2e' }) }))
  await page.route('**/api/v1/bank-accounts?**', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: { items: [], page: 1, page_size: 20, total: 0 }, trace_id: 'e2e' }) }))
  await page.route('**/api/v1/reconciliation/results**', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: { items: [], page: 1, page_size: 20, total: 0 }, trace_id: 'e2e' }) }))
  await page.route('**/api/v1/forecast/cashflow/latest', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: { job: null, results: [], evaluation: { evaluated_points: 0, mae: '0', rmse: '0', mean_deviation: '0' } }, trace_id: 'e2e' }) }))
  await page.route('**/api/v1/forecast/models', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: [], trace_id: 'e2e' }) }))
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
