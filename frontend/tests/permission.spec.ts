import { expect, test, type Page } from '@playwright/test'
import { expectAllGroupTabsVisible } from './helpers/nav'

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
  await page.route('**/api/v1/auth/me', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: {
          id: 5,
          tenant_id: 1,
          login_name: 'biz01',
          display_name: '业务负责人',
          roles: ['BUSINESS'],
          permissions: BUSINESS_PERMISSIONS,
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

test('业务负责人菜单按权限过滤且默认落地项目资金', async ({ page }) => {
  await mockBusinessApi(page)
  await loginAsBusiness(page)

  // P4-19：BUSINESS 角色默认落地项目资金（无 dashboard:view 时不再进 403）
  await expect(page).toHaveURL(/#\/projects$/)
  await expect(page.getByRole('heading', { name: '项目资金与风险' })).toBeVisible()

  await expect(page.getByRole('link', { name: '项目资金' })).toBeVisible()
  // P6-2：BUSINESS 默认仅展开基础资料；展开第二步 · 回款处理后可见待办处理与合同与应收入口
  await expect(page.getByRole('link', { name: '合同与应收' })).toBeHidden()
  await page.getByRole('button', { name: '第二步 · 回款处理' }).click()
  await expect(page.getByRole('link', { name: '待办处理' })).toBeVisible()
  await expect(page.getByRole('link', { name: '合同与应收' })).toBeVisible()
  await expect(page.getByRole('link', { name: '管理驾驶舱' })).toBeHidden()
  await expect(page.getByRole('link', { name: '银行账户管理' })).toBeHidden()
  await expect(page.getByRole('link', { name: '资金报表' })).toBeHidden()
})

test('业务负责人在项目页看不到受限操作按钮', async ({ page }) => {
  await mockBusinessApi(page)
  await loginAsBusiness(page)

  await page.getByRole('link', { name: '项目资金' }).click()
  await expect(page.getByRole('heading', { name: '项目资金与风险' })).toBeVisible()
  await expect(page.getByRole('button', { name: '批量启用' })).toBeHidden()
  await expect(page.getByRole('button', { name: '批量完成' })).toBeHidden()

  await page.getByRole('button', { name: '第二步 · 回款处理' }).click()
  await page.getByRole('link', { name: '合同与应收' }).click()
  await expect(page.getByRole('heading', { name: '合同列表' })).toBeVisible()
  await expect(page.getByRole('button', { name: '导入合同应收' })).toBeHidden()

  await page.getByRole('link', { name: '待办处理' }).click()
  await expect(page.getByRole('heading', { name: '待办处理' })).toBeVisible()
  await expect(page.getByRole('button', { name: '一键匹配' })).toBeHidden()
})

test('业务负责人直接访问无权限路由进入 403 提示页', async ({ page }) => {
  await mockBusinessApi(page)
  await loginAsBusiness(page)

  await page.goto('/?direct=1#/reports')
  await expect(page).toHaveURL(/#\/403$/)
  await expect(page.getByRole('heading', { name: '没有访问权限' })).toBeVisible()
})

const WORKBENCH_PERMISSIONS = [
  'dashboard:view',
  'transaction:view',
  'matching:view',
  'matching:confirm',
  'exception:view',
  'exception:handle',
]

const WORKBENCH_STATS = {
  active_count: 8,
  unassigned_count: 3,
  my_todo_count: 5,
  pending_close_count: 2,
  overdue_count: 1,
  closed_count: 0,
  false_positive_count: 0,
  total_count: 10,
  closure_rate: 0,
  avg_resolution_hours: 0,
  today_new_count: 2,
  review_pending_count: 2,
  large_amount_count: 1,
  large_amount_threshold: 100000,
  by_type: [],
  by_owner: [],
}

async function mockWorkbenchStats(page: Page) {
  await page.route('**/api/v1/matching/exceptions/stats', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: WORKBENCH_STATS, trace_id: 'e2e' }),
    }),
  )
}

async function mockRoleApi(page: Page, loginName: string, role: string) {
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
            id: 9,
            tenant_id: 1,
            login_name: loginName,
            display_name: loginName,
            roles: [role],
            permissions: WORKBENCH_PERMISSIONS,
          },
        },
        trace_id: 'e2e',
      }),
    }),
  )
  await mockWorkbenchStats(page)
  await page.route('**/api/v1/matching/exceptions?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: EMPTY_PAGE }),
  )
  await page.route('**/api/v1/dashboard/**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: {}, trace_id: 'e2e' }),
    }),
  )
}

async function loginWith(page: Page) {
  await page.goto('/')
  await page.getByLabel('密码').fill('e2e-password')
  await page.getByRole('button', { name: '登录' }).click()
}

test('出纳默认落地待办处理且角标显示我的待办', async ({ page }) => {
  await mockRoleApi(page, 'cashier01', 'CASHIER')
  await loginWith(page)
  await expect(page).toHaveURL(/#\/exceptions/)
  await expect(page.getByRole('heading', { name: '待办处理' })).toBeVisible()
  await expect(page.getByRole('link', { name: /待办处理/ }).locator('.nav-badge')).toHaveText('5')
})

test('CFO 默认落驾驶舱且角标显示待分派加待复核', async ({ page }) => {
  await mockRoleApi(page, 'finance01', 'CFO')
  await loginWith(page)
  await expect(page).toHaveURL(/#\/dashboard$/)
  // 顶栏形态（方向 A）：二级条只显示当前组，断言角标前先切到回款处理组
  await page.getByRole('button', { name: '第二步 · 回款处理', exact: true }).click()
  await expect(page.getByRole('link', { name: /待办处理/ }).locator('.nav-badge')).toHaveText('5')
})

const ADMIN_PERMISSIONS = [
  'dashboard:view',
  'account:view',
  'transaction:view',
  'import:view',
  'contract:view',
  'project:view',
  'project:rule',
  'matching:view',
  'exception:view',
  'reconciliation:view',
  'report:view',
  'forecast:view',
  'template:manage',
  'system:manage',
]

async function mockAdminApi(page: Page) {
  const adminUser = {
    id: 1,
    tenant_id: 1,
    login_name: 'admin01',
    display_name: '系统管理员',
    roles: ['ADMIN'],
    permissions: ADMIN_PERMISSIONS,
  }
  await page.route('**/api/v1/auth/login', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: { access_token: 'e2e-token', user: adminUser },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/auth/me', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: adminUser, trace_id: 'e2e' }),
    }),
  )
  await mockWorkbenchStats(page)
  await page.route('**/api/v1/system/users?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: EMPTY_PAGE }),
  )
  await page.route('**/api/v1/system/roles', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: [], trace_id: 'e2e' }),
    }),
  )
  await page.route('**/api/v1/system/permissions', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: [], trace_id: 'e2e' }),
    }),
  )
  await page.route('**/api/v1/projects?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: EMPTY_PAGE }),
  )
}

test('管理员六组默认全部展开且角标按 CFO 口径显示', async ({ page }) => {
  await mockAdminApi(page)
  await loginWith(page)

  // P6-1：ADMIN 落地用户与权限（landingPath 本期不变）。
  // 顶栏形态（方向 A）无「分组展开」概念，等价断言为：六组一级 tab 均可见，
  // 且逐组切换后该组二级链接可见（证明 ADMIN 六组全部可达）
  await expect(page).toHaveURL(/#\/system$/)
  await expectAllGroupTabsVisible(page)
  await expect(page.getByRole('link', { name: '用户与权限' })).toBeVisible()
  for (const [group, firstItem] of [
    ['第一步 · 数据接入', '银行账户管理'],
    ['第二步 · 回款处理', '合同与应收'],
    ['第三步 · 财务对账', '财务对账'],
    ['经营洞察', '管理驾驶舱'],
    ['基础资料', '项目资金'],
  ] as const) {
    await page.getByRole('button', { name: group, exact: true }).click()
    await expect(page.getByRole('link', { name: firstItem })).toBeVisible()
  }
  // P6-1：角标按 CFO 口径，待分派 3 + 待复核 2 = 5
  await page.getByRole('button', { name: '第二步 · 回款处理', exact: true }).click()
  await expect(page.getByRole('link', { name: /待办处理/ }).locator('.nav-badge')).toHaveText('5')
})
