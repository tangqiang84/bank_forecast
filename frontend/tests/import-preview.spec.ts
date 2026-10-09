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
  'receipt:view',
  'receipt:import',
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

  await page.getByRole('button', { name: '导入合同 / 应收计划' }).click()
  const importDialog = page.getByRole('dialog', { name: '导入合同 / 应收计划' })
  await importDialog.locator('input[type="file"]').setInputFiles({
    name: 'contracts.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('合同编号,计划金额\nHT-2026-001,400.00\n'),
  })
  await importDialog.getByRole('button', { name: '预览导入' }).click()
  await expect(importDialog.getByText('预览完成，请核对后确认导入。')).toBeVisible()
  expect(previewRequested).toBe(true)
  await expect(importDialog.getByText('任务 #301', { exact: true })).toBeVisible()
  await expect(importDialog.getByText('有效 1')).toBeVisible()
  await expect(importDialog.getByText('失败 1')).toBeVisible()
  await expect(importDialog.getByText('HT-2026-001')).toBeVisible()
  await expect(importDialog.getByText('计划金额格式不正确')).toBeVisible()

  await importDialog.getByRole('button', { name: '确认导入' }).click()
  await expect(importDialog.getByText('导入已确认，应收计划已更新。')).toBeVisible()
  expect(confirmRequested).toBe(true)
  await expect(importDialog.getByRole('button', { name: '关闭', exact: true })).toBeVisible()
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

  await page.getByRole('button', { name: '对账', exact: true }).click()
  const reconDialog = page.getByRole('dialog', { name: '银行账 / 财务账对账' })
  await reconDialog.locator('input[type="file"]').setInputFiles({
    name: 'finance-records.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('记录编号,金额\nFR-0001,500.00\n'),
  })
  await reconDialog.getByRole('button', { name: '预览导入' }).click()
  await expect(reconDialog.getByText('预览完成，请核对后确认导入。')).toBeVisible()
  expect(previewRequested).toBe(true)
  await expect(reconDialog.getByText('任务 #302', { exact: true })).toBeVisible()
  await expect(reconDialog.getByText('有效 1')).toBeVisible()
  await expect(reconDialog.getByText('失败 1')).toBeVisible()
  await expect(reconDialog.getByText('FR-0001')).toBeVisible()
  await expect(reconDialog.getByText('记录日期缺失')).toBeVisible()

  await reconDialog.getByRole('button', { name: '确认导入' }).click()
  await expect(reconDialog.getByText('导入已确认，财务记录已入库。')).toBeVisible()
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

  await page.getByRole('button', { name: '导入项目' }).click()
  const importDialog = page.getByRole('dialog', { name: '导入项目' })
  await importDialog.locator('input[type="file"]').setInputFiles({
    name: 'projects.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('项目编号,项目名称\nPJ-2026-001,示例项目\n'),
  })
  await importDialog.getByRole('button', { name: '预览导入' }).click()
  await expect(importDialog.getByText('预览完成，请核对后确认导入。')).toBeVisible()
  expect(previewRequested).toBe(true)
  await expect(importDialog.getByText('任务 #303', { exact: true })).toBeVisible()
  await expect(importDialog.getByText('有效 1')).toBeVisible()
  await expect(importDialog.getByText('失败 1')).toBeVisible()
  await expect(importDialog.getByText('PJ-2026-001')).toBeVisible()
  await expect(importDialog.getByText('项目状态不合法')).toBeVisible()

  await importDialog.getByRole('button', { name: '确认导入' }).click()
  await expect(importDialog.getByText('导入已确认，项目清单已更新。')).toBeVisible()
  expect(confirmRequested).toBe(true)
  await expect(importDialog.getByRole('button', { name: '关闭', exact: true })).toBeVisible()
})

const receiptPreviewPayload = {
  job_id: 304,
  job_type: 'receipt',
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
        receipt_no: 'RC-2026-001',
        transaction_date: '2026-09-20',
        amount: '1000.00',
        payer_name: '示例客户',
        payee_name: '演示企业',
        transaction_no: 'TXN-1',
        currency: 'CNY',
        summary: '货款',
      },
    },
    {
      id: 2,
      row_no: 2,
      status: 'failed',
      error_message: '金额格式不正确',
      payload: {
        receipt_no: 'RC-2026-002',
        transaction_date: '2026-09-21',
        amount: 'abc',
        payer_name: null,
        payee_name: null,
        transaction_no: null,
      },
    },
  ],
  error_details: [
    {
      row_no: 2,
      field_name: 'amount',
      raw_json: '{"amount":"abc"}',
      error_message: '金额格式不正确',
    },
  ],
}

test('回单导入预览后确认导入', async ({ page }) => {
  let previewRequested = false
  let confirmRequested = false

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/bank-accounts?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
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
      }),
    }),
  )
  await page.route('**/api/v1/bank-transactions?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(EMPTY_PAGE) }),
  )
  await page.route('**/api/v1/imports/receipts/preview', async (route) => {
    previewRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(receiptPreviewPayload),
    })
  })
  await page.route('**/api/v1/imports/304/confirm', async (route) => {
    confirmRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ ...receiptPreviewPayload, status: 'confirmed', failed_rows: 0 }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '银行流水' }).click()
  await expect(page.getByRole('heading', { name: '流水列表' })).toBeVisible()

  await page.getByRole('button', { name: '导入回单' }).click()
  const receiptPanel = page.getByRole('dialog', { name: '导入回单' })
  await receiptPanel.locator('input[type="file"]').setInputFiles({
    name: 'receipts.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('回单号,交易日期,金额\nRC-2026-001,2026-09-20,1000.00\n'),
  })
  await receiptPanel.getByRole('button', { name: '预览导入' }).click()
  await expect(page.getByText('预览完成，请核对后确认导入。')).toBeVisible()
  expect(previewRequested).toBe(true)
  await expect(page.getByText('任务 #304', { exact: true })).toBeVisible()

  await receiptPanel.getByRole('button', { name: '确认导入' }).click()
  await expect(receiptPanel.getByText('回单导入已确认。')).toBeVisible()
  expect(confirmRequested).toBe(true)
  await expect(receiptPanel.getByRole('button', { name: '关闭', exact: true })).toBeVisible()
})

const statementDuplicatePreviewPayload = {
  job_id: 105,
  status: 'preview_pending',
  total_rows: 2,
  success_rows: 1,
  failed_rows: 0,
  skipped_rows: 0,
  duplicate_suspected_rows: 1,
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
    {
      id: 2,
      row_no: 2,
      transaction_no: 'TXN-2',
      transaction_date: '2026-09-01',
      direction: 'income',
      amount: '100.00',
      balance_after: null,
      counterparty_name: '示例客户',
      summary: '回款',
      status: 'duplicate_suspect',
      error_message: '与账户已有流水疑似重复（弱指纹）',
    },
  ],
  recognized_templates: [],
}

test('流水导入疑似重复行勾选后随确认入账', async ({ page }) => {
  let confirmBody: Record<string, unknown> | null = null

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/bank-accounts?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
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
      }),
    }),
  )
  await page.route('**/api/v1/bank-transactions?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(EMPTY_PAGE) }),
  )
  await page.route('**/api/v1/imports/bank-statements/preview', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(statementDuplicatePreviewPayload),
    }),
  )
  await page.route('**/api/v1/imports/105/confirm', async (route) => {
    const raw = route.request().postData()
    confirmBody = raw ? (JSON.parse(raw) as Record<string, unknown>) : null
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ ...statementDuplicatePreviewPayload, status: 'confirmed', preview_rows: [] }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '银行流水' }).click()
  await page.getByRole('button', { name: '导入流水' }).click()
  const statementPanel = page.getByRole('dialog', { name: '导入流水' })
  await statementPanel.locator('input[type="file"]').setInputFiles({
    name: 'statement.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('交易日期,金额\n2026-09-01,100.00\n2026-09-01,100.00\n'),
  })
  await statementPanel.getByRole('button', { name: '预览导入' }).click()
  await expect(statementPanel.getByText('预览完成，请核对后确认入账。')).toBeVisible()
  await expect(statementPanel.getByText('疑似重复 1')).toBeVisible()
  await expect(statementPanel.getByText('同时导入 1 条疑似重复流水')).toBeVisible()

  // 不勾选时确认不带请求体，疑似重复行默认不入账
  await statementPanel.getByRole('button', { name: '确认入账' }).click()
  await expect(statementPanel.getByText('已确认入账。')).toBeVisible()
  expect(confirmBody).toBeNull()
})

test('导入任务页展示疑似重复行并支持勾选强制导入', async ({ page }) => {
  let confirmBody: Record<string, unknown> | null = null
  const jobPayload = {
    ...statementDuplicatePreviewPayload,
    job_id: 106,
    job_type: 'bank_statement',
    error_details: [],
  }

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/auth/me', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        id: 1,
        tenant_id: 1,
        login_name: 'finance01',
        display_name: '财务负责人',
        roles: ['CFO'],
        permissions: ALL_PERMISSIONS,
      }),
    }),
  )
  await page.route('**/api/v1/imports/quality-summary**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        days: 30,
        job_count: 4,
        total_rows: 100,
        success_rows: 90,
        failed_rows: 6,
        skipped_rows: 2,
        duplicate_suspected_rows: 2,
        success_rate: 90,
        duplicate_suspect_rate: 2,
      }),
    }),
  )
  await page.route('**/api/v1/imports/106/preview', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(jobPayload) }),
  )
  await page.route('**/api/v1/imports/106/confirm', async (route) => {
    const raw = route.request().postData()
    confirmBody = raw ? (JSON.parse(raw) as Record<string, unknown>) : null
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ ...jobPayload, status: 'confirmed' }),
    })
  })

  await login(page)
  await page.goto('/#/imports/106')
  await expect(page.getByRole('heading', { name: '任务 #106' })).toBeVisible()

  // 近 30 天导入质量汇总卡
  await expect(page.getByRole('heading', { name: '近 30 天导入质量' })).toBeVisible()
  await expect(page.getByText('任务数 4')).toBeVisible()
  await expect(page.getByText('成功率 90%')).toBeVisible()
  await expect(page.getByText('疑似重复率 2%')).toBeVisible()
  await expect(page.getByText('失败行数 6')).toBeVisible()

  // 预览行疑似重复状态与提示
  await expect(page.getByText('与账户已有流水疑似重复（弱指纹）')).toBeVisible()
  const duplicateRow = page.locator('tr').filter({ hasText: '与账户已有流水疑似重复' })
  await expect(duplicateRow.getByText('疑似重复', { exact: true })).toBeVisible()

  // 勾选后确认请求体带 include_duplicates
  await page.getByText('同时导入 1 条疑似重复流水').click()
  await page.getByRole('button', { name: '确认入账' }).click()
  await expect(page.getByText('任务已确认入账。')).toBeVisible()
  expect(confirmBody).toEqual({ include_duplicates: [2] })
})
