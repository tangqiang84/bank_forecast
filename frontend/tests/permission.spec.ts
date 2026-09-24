import { expect, test, type Page } from '@playwright/test'

const BUSINESS_PERMISSIONS = [
  'project:view',
  'contract:view',
  'matching:view',
  'exception:view',
  'exception:handle',
  'attachment:view',
  'attachment:manage',
]

const EMPTY_PAGE = JSON.stringify({
  code: 0,
  message: 'ok',
  data: { items: [], page: 1, page_size: 20, total: 0 },
  trace_id: 'e2e',
})

async function mockBusinessApi(page: Page) {
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
            id: 5,
            tenant_id: 1,
            login_name: 'biz01',
            display_name: '业务负责人',
            roles: ['BUSINESS'],
            permissions: BUSINESS_PERMISSIONS,
          },
        },
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
    route.fulfill({ status: 200, contentType: 'application/json', body: EMPTY_PAGE }),
  )
  await page.route('**/api/v1/contracts/receivables**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: EMPTY_PAGE }),
  )
  await page.route('**/api/v1/matching/results?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: EMPTY_PAGE }),
  )
  await page.route('**/api/v1/matching/exceptions?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: EMPTY_PAGE }),
  )
}

async function loginAsBusiness(page: Page) {
  await page.goto('/')
  await page.getByLabel('密码').fill('e2e-password')
  await page.getByRole('button', { name: '登录' }).click()
}

test('业务负责人菜单按权限过滤且默认页进入 403 提示页', async ({ page }) => {
  await mockBusinessApi(page)
  await loginAsBusiness(page)

  await expect(page).toHaveURL(/#\/403$/)
  await expect(page.getByRole('heading', { name: '没有访问权限' })).toBeVisible()

  await expect(page.getByRole('link', { name: '项目资金' })).toBeVisible()
  await expect(page.getByRole('link', { name: '合同应收' })).toBeVisible()
  await expect(page.getByRole('link', { name: '匹配结果' })).toBeVisible()
  await expect(page.getByRole('link', { name: '异常事项' })).toBeVisible()
  await expect(page.getByRole('link', { name: '驾驶舱' })).toBeHidden()
  await expect(page.getByRole('link', { name: '银行账户' })).toBeHidden()
  await expect(page.getByRole('link', { name: '报表中心' })).toBeHidden()
})

test('业务负责人在项目页看不到受限操作按钮', async ({ page }) => {
  await mockBusinessApi(page)
  await loginAsBusiness(page)

  await page.getByRole('link', { name: '项目资金' }).click()
  await expect(page.getByRole('heading', { name: '项目资金与风险' })).toBeVisible()
  await expect(page.getByRole('button', { name: '批量启用' })).toBeHidden()
  await expect(page.getByRole('button', { name: '批量完成' })).toBeHidden()

  await page.getByRole('link', { name: '合同应收' }).click()
  await expect(page.getByRole('heading', { name: '合同回款计划' })).toBeVisible()
  await expect(page.getByRole('button', { name: '导入合同应收' })).toBeHidden()
  await expect(page.getByRole('button', { name: '运行回款匹配' })).toBeHidden()
})

test('业务负责人直接访问无权限路由进入 403 提示页', async ({ page }) => {
  await mockBusinessApi(page)
  await loginAsBusiness(page)

  await page.goto('/#/reports')
  await expect(page).toHaveURL(/#\/403$/)
  await expect(page.getByRole('heading', { name: '没有访问权限' })).toBeVisible()
})
