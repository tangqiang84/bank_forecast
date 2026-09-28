import { Buffer } from 'node:buffer'
import { expect, test, type Page } from '@playwright/test'

const ALL_PERMISSIONS = [
  'dashboard:view',
  'account:view',
  'account:manage',
  'account:scan',
  'transaction:view',
  'transaction:import',
  'transaction:export',
  'import:view',
  'contract:view',
  'contract:import',
  'project:view',
  'project:manage',
  'project:rule',
  'project:import',
  'matching:view',
  'matching:run',
  'matching:confirm',
  'exception:view',
  'exception:assign',
  'exception:handle',
  'attachment:view',
  'attachment:manage',
  'reconciliation:view',
  'reconciliation:run',
  'report:view',
  'report:generate',
  'report:download',
  'forecast:view',
  'forecast:run',
  'forecast:model',
  'audit:view',
]

const EMPTY_PAGE = { items: [], page: 1, page_size: 20, total: 0 }

function ok(data: unknown) {
  return JSON.stringify({ code: 0, message: 'ok', data, trace_id: 'e2e' })
}

async function mockLoginAndDashboard(page: Page) {
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
          roles: ['CFO'],
          permissions: ALL_PERMISSIONS,
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

test('合同导入预览后确认导入', async ({ page }) => {
  let previewRequested = false
  let confirmRequested = false

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/contracts/receivables?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(EMPTY_PAGE) }),
  )
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
  await page.getByRole('link', { name: '合同应收' }).click()
  await expect(page.getByRole('heading', { name: '合同回款计划' })).toBeVisible()

  await page.locator('input[type="file"]').setInputFiles({
    name: 'contracts.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('合同编号,计划金额\nHT-2026-001,400.00\n'),
  })
  await page.getByRole('button', { name: '预览导入' }).click()
  await expect(page.getByText('预览完成，请核对后确认导入。')).toBeVisible()
  expect(previewRequested).toBe(true)
  await expect(page.getByText('任务 #301', { exact: true })).toBeVisible()
  await expect(page.getByText('有效 1')).toBeVisible()
  await expect(page.getByText('失败 1')).toBeVisible()
  await expect(page.getByText('HT-2026-001')).toBeVisible()
  await expect(page.getByText('计划金额格式不正确')).toBeVisible()

  await page.getByRole('button', { name: '确认导入' }).click()
  await expect(page.getByText('导入已确认，应收计划已更新。')).toBeVisible()
  expect(confirmRequested).toBe(true)
})

const financePreviewPayload = {
  job_id: 302,
  job_type: 'finance_record',
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
        record_no: 'FR-0001',
        record_type: 'receipt',
        record_date: '2026-09-01',
        posting_date: null,
        counterparty_name: '示例客户',
        amount: '500.00',
        summary: '回款',
        source_system: 'ERP',
        contract_no: 'HT-2026-001',
        project_no: null,
        remark: null,
      },
    },
    {
      id: 2,
      row_no: 2,
      status: 'failed',
      error_message: '记录日期缺失',
      payload: {
        record_no: 'FR-0002',
        record_type: 'receipt',
        record_date: null,
        posting_date: null,
        counterparty_name: '示例客户',
        amount: '200.00',
        summary: null,
        source_system: 'ERP',
        contract_no: null,
        project_no: null,
        remark: null,
      },
    },
  ],
  error_details: [
    {
      row_no: 2,
      field_name: 'record_date',
      raw_json: '{"record_no":"FR-0002"}',
      error_message: '记录日期缺失',
    },
  ],
}

test('财务记录导入预览后确认导入', async ({ page }) => {
  let previewRequested = false
  let confirmRequested = false

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/reconciliation/results?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(EMPTY_PAGE) }),
  )
  await page.route('**/api/v1/imports/finance-records/preview', async (route) => {
    previewRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(financePreviewPayload),
    })
  })
  await page.route('**/api/v1/imports/302/confirm', async (route) => {
    confirmRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ ...financePreviewPayload, status: 'confirmed', failed_rows: 0 }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '财务对账' }).click()
  await expect(page.getByRole('heading', { name: '银行账 / 财务账对账' })).toBeVisible()

  await page.locator('input[type="file"]').setInputFiles({
    name: 'finance-records.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('记录编号,金额\nFR-0001,500.00\n'),
  })
  await page.getByRole('button', { name: '预览导入' }).click()
  await expect(page.getByText('预览完成，请核对后确认导入。')).toBeVisible()
  expect(previewRequested).toBe(true)
  await expect(page.getByText('任务 #302', { exact: true })).toBeVisible()
  await expect(page.getByText('有效 1')).toBeVisible()
  await expect(page.getByText('失败 1')).toBeVisible()
  await expect(page.getByText('FR-0001')).toBeVisible()
  await expect(page.getByText('记录日期缺失')).toBeVisible()

  await page.getByRole('button', { name: '确认导入' }).click()
  await expect(page.getByText('导入已确认，财务记录已入库。')).toBeVisible()
  expect(confirmRequested).toBe(true)
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

test('项目导入预览后确认导入', async ({ page }) => {
  let previewRequested = false
  let confirmRequested = false

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/projects?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(EMPTY_PAGE) }),
  )
  await page.route('**/api/v1/projects/risk-rules', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok([]) }),
  )
  await page.route('**/api/v1/imports/projects/preview', async (route) => {
    previewRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(projectPreviewPayload),
    })
  })
  await page.route('**/api/v1/imports/303/confirm', async (route) => {
    confirmRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ ...projectPreviewPayload, status: 'confirmed', failed_rows: 0 }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '项目资金' }).click()
  await expect(page.getByRole('heading', { name: '项目资金与风险' })).toBeVisible()

  await page.locator('input[type="file"]').setInputFiles({
    name: 'projects.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('项目编号,项目名称\nPJ-2026-001,示例项目\n'),
  })
  await page.getByRole('button', { name: '预览导入' }).click()
  await expect(page.getByText('预览完成，请核对后确认导入。')).toBeVisible()
  expect(previewRequested).toBe(true)
  await expect(page.getByText('任务 #303', { exact: true })).toBeVisible()
  await expect(page.getByText('有效 1')).toBeVisible()
  await expect(page.getByText('失败 1')).toBeVisible()
  await expect(page.getByText('PJ-2026-001')).toBeVisible()
  await expect(page.getByText('项目状态不合法')).toBeVisible()

  await page.getByRole('button', { name: '确认导入' }).click()
  await expect(page.getByText('导入已确认，项目清单已更新。')).toBeVisible()
  expect(confirmRequested).toBe(true)
})
