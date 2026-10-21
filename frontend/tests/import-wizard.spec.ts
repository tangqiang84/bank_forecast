import { Buffer } from 'node:buffer'
import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return JSON.stringify({ code: 0, message: 'ok', data, trace_id: 'e2e' })
}

const QUALITY_SUMMARY = {
  days: 30,
  job_count: 4,
  total_rows: 100,
  success_rows: 90,
  failed_rows: 6,
  skipped_rows: 2,
  duplicate_suspected_rows: 2,
  success_rate: 90,
  duplicate_suspect_rate: 2,
}

const BANK_ACCOUNTS_PAGE = {
  items: [
    {
      id: 1,
      bank_code: 'ICBC',
      bank_name: '工商银行',
      account_name: '基本户',
      account_no_last4: '1234',
      currency: 'CNY',
      status: 'active',
      current_balance: '100.00',
    },
  ],
  page: 1,
  page_size: 20,
  total: 1,
}

async function mockBase(page: Page, permissions: string[], roles: string[] = []) {
  await page.route('**/api/v1/auth/login', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        access_token: 'e2e-token',
        user: {
          id: 1,
          tenant_id: 1,
          login_name: 'e2e',
          display_name: '端到端用户',
          roles,
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
  await page.route('**/api/v1/imports/quality-summary**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(QUALITY_SUMMARY) }),
  )
}

async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('密码').fill('e2e-password')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/#\/dashboard$/)
}

const contractPreviewPayload = {
  job_id: 301,
  job_type: 'contract',
  status: 'preview_pending',
  total_rows: 2,
  success_rows: 1,
  failed_rows: 1,
  skipped_rows: 0,
  preview_rows: [
    {
      id: 1,
      row_no: 1,
      status: 'valid',
      error_message: null,
      payload: {
        contract_no: 'HT-2026-001',
        contract_name: '示例合同',
        customer_name: '示例客户',
        project_no: null,
        project_name: null,
        contract_amount: '1000.00',
        node_name: '首期回款',
        node_type: 'milestone',
        due_date: '2026-10-01',
        plan_amount: '400.00',
        owner_name: null,
      },
    },
    {
      id: 2,
      row_no: 2,
      status: 'failed',
      error_message: '计划金额格式不正确',
      payload: {
        contract_no: 'HT-2026-002',
        contract_name: '异常合同',
        customer_name: '示例客户',
        project_no: null,
        project_name: null,
        contract_amount: null,
        node_name: '尾款',
        node_type: 'milestone',
        due_date: '2026-11-01',
        plan_amount: 'abc',
        owner_name: null,
      },
    },
  ],
  error_details: [
    {
      row_no: 2,
      field_name: 'plan_amount',
      raw_json: '{"plan_amount":"abc"}',
      error_message: '计划金额格式不正确',
    },
  ],
}

test('导入向导卡片按权限过滤且质量卡渲染', async ({ page }) => {
  await mockBase(page, ['dashboard:view', 'project:import'])

  await login(page)
  await page.getByRole('link', { name: '导入向导' }).click()
  await expect(page).toHaveURL(/#\/imports$/)
  await expect(page.getByRole('heading', { name: '导入向导' })).toBeVisible()

  // 近 30 天导入质量卡
  await expect(page.getByRole('heading', { name: '近 30 天导入质量' })).toBeVisible()
  await expect(page.getByText('任务数 4')).toBeVisible()
  await expect(page.getByText('成功率 90%')).toBeVisible()

  // 只有 project:import：仅项目卡可见
  await expect(page.getByRole('button', { name: /项目/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /银行流水/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /电子回单/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /合同与应收计划/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /应付计划/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /财务记录/ })).toHaveCount(0)
})

test('仅 import:view 无任何导入权限时显示空态提示', async ({ page }) => {
  await mockBase(page, ['dashboard:view', 'import:view'])

  await login(page)
  await page.goto('/#/imports')
  await expect(page.getByText('没有可用的导入权限，请联系管理员')).toBeVisible()
})

test('合同导入完整动线：选卡、上传、预览、确认、成功计数与详情链接', async ({ page }) => {
  let previewRequested = false
  let confirmRequested = false

  await mockBase(page, ['dashboard:view', 'contract:import', 'import:view'])
  await page.route('**/api/v1/imports/contracts/preview', async (route) => {
    previewRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(contractPreviewPayload),
    })
  })
  await page.route('**/api/v1/imports/301/confirm', async (route) => {
    confirmRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ ...contractPreviewPayload, status: 'confirmed', failed_rows: 0 }),
    })
  })

  await login(page)
  await page.goto('/#/imports')

  await page.getByRole('button', { name: /合同与应收计划/ }).click()
  await expect(page.getByRole('heading', { name: '上传合同与应收计划文件' })).toBeVisible()

  const uploadPanel = page.locator('article.panel').filter({
    has: page.getByRole('heading', { name: '上传合同与应收计划文件' }),
  })
  await uploadPanel.locator('input[type="file"]').setInputFiles({
    name: 'contracts.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('合同编号,计划金额\nHT-2026-001,400.00\n'),
  })
  await uploadPanel.getByRole('button', { name: '开始预览' }).click()
  expect(previewRequested).toBe(true)

  const previewPanel = page.locator('article.panel').filter({
    has: page.getByRole('heading', { name: '预览合同与应收计划' }),
  })
  await expect(previewPanel.getByText('预览完成，请核对后确认导入。')).toBeVisible()
  await expect(previewPanel.getByText('任务 #301', { exact: true })).toBeVisible()
  await expect(previewPanel.getByText('有效 1')).toBeVisible()
  await expect(previewPanel.getByText('失败 1')).toBeVisible()
  await expect(previewPanel.getByText('HT-2026-001')).toBeVisible()
  await expect(previewPanel.getByText('计划金额格式不正确')).toBeVisible()

  await previewPanel.getByRole('button', { name: '确认导入' }).click()
  expect(confirmRequested).toBe(true)

  const donePanel = page.locator('article.panel').filter({
    has: page.getByRole('heading', { name: '导入完成' }),
  })
  await expect(donePanel.getByText('任务 #301', { exact: true })).toBeVisible()
  await expect(donePanel.getByText('入账 1')).toBeVisible()
  await expect(donePanel.getByText('失败 0')).toBeVisible()
  // 持有 import:view：提供任务详情链接
  await expect(donePanel.getByRole('link', { name: '查看导入任务详情 →' })).toHaveAttribute(
    'href',
    '#/imports/301',
  )

  await donePanel.getByRole('button', { name: '继续导入其他数据' }).click()
  await expect(page.getByRole('heading', { name: '选择导入类型' })).toBeVisible()
})

test('银行流水卡要求先选账户再预览', async ({ page }) => {
  let previewRequested = false

  await mockBase(page, ['dashboard:view', 'transaction:import'])
  await page.route('**/api/v1/bank-accounts?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(BANK_ACCOUNTS_PAGE) }),
  )
  await page.route('**/api/v1/imports/bank-statements/preview', async (route) => {
    previewRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        job_id: 105,
        status: 'preview_pending',
        total_rows: 1,
        success_rows: 1,
        failed_rows: 0,
        skipped_rows: 0,
        preview_rows: [
          {
            id: 1,
            row_no: 1,
            transaction_no: 'TXN-1',
            transaction_date: '2026-09-01',
            direction: 'income',
            amount: '100.00',
            balance_after: null,
            counterparty_name: '示例客户',
            summary: '回款',
            status: 'valid',
            error_message: null,
          },
        ],
        recognized_templates: [],
      }),
    })
  })

  await login(page)
  await page.goto('/#/imports')
  await page.getByRole('button', { name: /银行流水/ }).click()

  const uploadPanel = page.locator('article.panel').filter({
    has: page.getByRole('heading', { name: '上传银行流水文件' }),
  })
  // 账户选择器出现，未选账户且未选文件时不能提交
  await expect(uploadPanel.getByLabel('银行账户')).toBeVisible()
  const previewButton = uploadPanel.getByRole('button', { name: '开始预览' })
  await expect(previewButton).toBeDisabled()

  await uploadPanel.locator('input[type="file"]').setInputFiles({
    name: 'statement.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('交易日期,金额\n2026-09-01,100.00\n'),
  })
  // 已选文件但未选账户仍不能提交
  await expect(previewButton).toBeDisabled()

  await uploadPanel.getByLabel('银行账户').selectOption({ label: '工商银行 · 基本户 · 1234' })
  await expect(previewButton).toBeEnabled()
  await previewButton.click()
  expect(previewRequested).toBe(true)
  await expect(page.getByText('任务 #105', { exact: true })).toBeVisible()
})

const projectPreviewPayload = {
  job_id: 303,
  job_type: 'project',
  status: 'preview_pending',
  total_rows: 2,
  success_rows: 1,
  failed_rows: 1,
  skipped_rows: 0,
  preview_rows: [
    {
      id: 1,
      row_no: 1,
      status: 'valid',
      error_message: null,
      payload: {
        project_no: 'PJ-2026-001',
        project_name: '示例项目',
        customer_name: '示例客户',
        project_manager: '李四',
        project_status: 'active',
        start_date: '2026-09-01',
        delivery_date: '2026-11-15',
        acceptance_date: null,
        remark: null,
      },
    },
    {
      id: 2,
      row_no: 2,
      status: 'failed',
      error_message: '项目状态不合法',
      payload: {
        project_no: 'PJ-2026-002',
        project_name: '异常项目',
        customer_name: null,
        project_manager: null,
        project_status: 'unknown',
        start_date: null,
        delivery_date: null,
        acceptance_date: null,
        remark: null,
      },
    },
  ],
  error_details: [
    {
      row_no: 2,
      field_name: 'project_status',
      raw_json: '{"project_status":"unknown"}',
      error_message: '项目状态不合法',
    },
  ],
}

test('无 import:view 权限的用户确认成功后不显示任务详情链接', async ({ page }) => {
  await mockBase(page, ['dashboard:view', 'project:import'], ['BUSINESS'])
  await page.route('**/api/v1/imports/projects/preview', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(projectPreviewPayload),
    }),
  )
  await page.route('**/api/v1/imports/303/confirm', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ ...projectPreviewPayload, status: 'confirmed', failed_rows: 0 }),
    }),
  )

  // BUSINESS 角色（有 project:import、无 import:view）落地到第一个可见菜单项：导入向导
  await page.goto('/')
  await page.getByLabel('密码').fill('e2e-password')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/#\/imports$/)
  await page.getByRole('button', { name: /项目/ }).click()

  const uploadPanel = page.locator('article.panel').filter({
    has: page.getByRole('heading', { name: '上传项目文件' }),
  })
  await uploadPanel.locator('input[type="file"]').setInputFiles({
    name: 'projects.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('项目编号,项目名称\nPJ-2026-001,示例项目\n'),
  })
  await uploadPanel.getByRole('button', { name: '开始预览' }).click()

  const previewPanel = page.locator('article.panel').filter({
    has: page.getByRole('heading', { name: '预览项目' }),
  })
  await expect(previewPanel.getByText('PJ-2026-001')).toBeVisible()
  await previewPanel.getByRole('button', { name: '确认导入' }).click()

  const donePanel = page.locator('article.panel').filter({
    has: page.getByRole('heading', { name: '导入完成' }),
  })
  await expect(donePanel.getByText('任务 #303', { exact: true })).toBeVisible()
  await expect(donePanel.getByText('入账 1')).toBeVisible()
  // BUSINESS 角色无 import:view：成功态不给出详情链接
  await expect(donePanel.getByRole('link', { name: '查看导入任务详情 →' })).toHaveCount(0)
  await expect(donePanel.getByRole('button', { name: '继续导入其他数据' })).toBeVisible()
})
