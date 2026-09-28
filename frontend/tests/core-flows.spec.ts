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

function postBody(route: { request: () => { postData: () => string | null } }) {
  return JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>
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

test('导入预览后确认入账', async ({ page }) => {
  let previewRequested = false
  let confirmRequested = false
  const previewPayload = {
    job_id: 101,
    status: 'preview_pending',
    total_rows: 3,
    success_rows: 2,
    failed_rows: 1,
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
        status: 'success',
        error_message: null,
      },
      {
        id: 2,
        row_no: 2,
        transaction_no: null,
        transaction_date: null,
        direction: null,
        amount: null,
        balance_after: null,
        counterparty_name: null,
        summary: null,
        status: 'failed',
        error_message: '金额格式不正确',
      },
    ],
    recognized_templates: [
      { sheet_name: 'Sheet1', bank_name: '示例银行', status: 'recognized', message: 'ok' },
    ],
  }

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
            current_balance: '1000.00',
            last_transaction_at: null,
            idle_days: null,
            idle_level: null,
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
  await page.route('**/api/v1/imports/bank-statements/preview', async (route) => {
    previewRequested = true
    await route.fulfill({ status: 200, contentType: 'application/json', body: ok(previewPayload) })
  })
  await page.route('**/api/v1/imports/101/confirm', async (route) => {
    confirmRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ ...previewPayload, status: 'confirmed', failed_rows: 0, preview_rows: [] }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '银行流水' }).click()
  await expect(page.getByRole('heading', { name: '流水列表' })).toBeVisible()

  await page.locator('input[type="file"]').setInputFiles({
    name: 'statement.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('交易日期,金额\n2026-09-01,100.00\n'),
  })
  await page.getByRole('button', { name: '预览导入' }).click()
  await expect(page.getByText('预览完成，请核对后确认入账。')).toBeVisible()
  expect(previewRequested).toBe(true)
  await expect(page.getByText('任务 #101')).toBeVisible()
  await expect(page.getByText('有效 2')).toBeVisible()
  await expect(page.getByText('失败 1')).toBeVisible()

  await page.getByRole('button', { name: '确认入账' }).click()
  await expect(page.getByText('已确认入账。')).toBeVisible()
  expect(confirmRequested).toBe(true)
})

function matchResult(id: number, transactionNo: string, status: string) {
  return {
    id,
    match_group_id: `MG-${id}`,
    allocation_mode: 'single',
    allocated_amount: '100.00',
    allocation_count: 1,
    allocation_total: '100.00',
    bank_transaction_id: id + 1000,
    transaction_no: transactionNo,
    amount: '100.00',
    transaction_match_status: status,
    contract_id: 1,
    contract_receivable_plan_id: 1,
    contract_no: 'HT-2026-001',
    contract_name: '示例合同',
    node_name: '首期回款',
    match_type: 'rule',
    confidence_level: 'medium',
    match_status: status,
    match_reason: '对方户名与合同客户一致',
    confirmed_by: null,
    confirmed_at: null,
  }
}

test('匹配结果确认与拒绝', async ({ page }) => {
  const state = { confirmed: false, rejected: false }
  let confirmRequested = false
  let rejectReason = ''

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/matching/results?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        items: [
          matchResult(501, 'TXN-9001', state.confirmed ? 'confirmed' : 'suggested'),
          matchResult(502, 'TXN-9002', state.rejected ? 'rejected' : 'suggested'),
        ],
        page: 1,
        page_size: 20,
        total: 2,
      }),
    }),
  )
  await page.route('**/api/v1/matching/results/501/confirm', async (route) => {
    confirmRequested = true
    state.confirmed = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(matchResult(501, 'TXN-9001', 'confirmed')),
    })
  })
  await page.route('**/api/v1/matching/results/502/reject', async (route) => {
    state.rejected = true
    rejectReason = String(postBody(route).reason ?? '')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(matchResult(502, 'TXN-9002', 'rejected')),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '匹配结果' }).click()
  await expect(page.getByRole('heading', { name: '回款匹配工作台' })).toBeVisible()

  const confirmRow = page.locator('tr', { hasText: 'TXN-9001' })
  await confirmRow.getByRole('button', { name: '确认组' }).click()
  await expect(page.getByText('匹配组已确认，应收和流水状态已更新。')).toBeVisible()
  expect(confirmRequested).toBe(true)
  await expect(confirmRow.locator('.pill')).toHaveText('confirmed')

  const rejectRow = page.locator('tr', { hasText: 'TXN-9002' })
  page.once('dialog', (dialog) => void dialog.accept('人工复核后拒绝'))
  await rejectRow.getByRole('button', { name: '拒绝组' }).click()
  await expect(page.getByText('匹配组已拒绝。')).toBeVisible()
  expect(rejectReason).toBe('人工复核后拒绝')
  await expect(rejectRow.locator('.pill')).toHaveText('rejected')
})

function exceptionCase(status: string) {
  return {
    id: 601,
    exception_no: 'EXC-0001',
    exception_type: 'unknown_income',
    title: '未知收款待确认',
    description: '流水未匹配到任何合同回款计划',
    status,
    severity: 'medium',
    due_date: '2026-09-30',
    owner_user_id: 1,
  }
}

test('异常事项备注并处理完成', async ({ page }) => {
  const state = { resolved: false }
  let commentText = ''
  let resolveText = ''

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/matching/exceptions?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        items: [exceptionCase(state.resolved ? 'resolved' : 'new')],
        page: 1,
        page_size: 20,
        total: 1,
      }),
    }),
  )
  await page.route('**/api/v1/matching/exceptions/601/comment', async (route) => {
    commentText = String(postBody(route).text ?? '')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(exceptionCase('new')),
    })
  })
  await page.route('**/api/v1/matching/exceptions/601/resolve', async (route) => {
    state.resolved = true
    resolveText = String(postBody(route).text ?? '')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(exceptionCase('resolved')),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '异常事项' }).click()
  await expect(page.getByRole('heading', { name: '异常处理工作台' })).toBeVisible()

  const row = page.locator('.exception-row', { hasText: 'EXC-0001' })
  await expect(row.getByText('未知收款待确认')).toBeVisible()
  await expect(row.locator('.pill')).toHaveText('new')

  const answers = ['已联系客户确认回款时间', '客户已回款，处理完成']
  page.on('dialog', (dialog) => void dialog.accept(answers.shift() ?? '人工处理记录'))

  await row.getByRole('button', { name: '备注' }).click()
  await expect(page.getByText('异常事项操作已完成。')).toBeVisible()
  expect(commentText).toBe('已联系客户确认回款时间')

  await row.getByRole('button', { name: '处理完成' }).click()
  await expect.poll(() => resolveText).toBe('客户已回款，处理完成')
  await expect(row.locator('.pill')).toHaveText('resolved')
  await expect(row.getByRole('button', { name: '关闭' })).toBeVisible()
})

const reportTask = {
  id: 701,
  report_type: 'monthly',
  date_from: '2026-09-01',
  date_to: '2026-09-30',
  status: 'success',
  file_name: 'monthly-2026-09.csv',
  error_message: null,
  created_at: '2026-09-28 10:00:00',
  updated_at: '2026-09-28 10:00:05',
}

test('生成报表并下载 CSV', async ({ page }) => {
  const state = { created: false }
  let createBody: Record<string, unknown> = {}

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/reports?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(
        state.created ? { items: [reportTask], page: 1, page_size: 20, total: 1 } : EMPTY_PAGE,
      ),
    }),
  )
  await page.route('**/api/v1/reports', async (route) => {
    state.created = true
    createBody = postBody(route)
    await route.fulfill({ status: 200, contentType: 'application/json', body: ok(reportTask) })
  })
  await page.route('**/api/v1/reports/701/download', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/csv',
      body: 'date,net_cashflow\n2026-09-01,100.00\n',
    }),
  )

  await login(page)
  await page.getByRole('link', { name: '报表中心' }).click()
  await expect(page.getByRole('heading', { name: '资金经营报告' })).toBeVisible()

  await page.getByRole('button', { name: '生成报表' }).click()
  await expect(page.getByText('报表已生成。')).toBeVisible()
  expect(createBody.report_type).toBe('monthly')
  expect(String((createBody.params_json as Record<string, string>)?.month ?? '')).toMatch(
    /^\d{4}-\d{2}$/,
  )

  const row = page.locator('tr', { hasText: '月报' })
  await expect(row.locator('.pill')).toHaveText('success')

  const downloadPromise = page.waitForEvent('download')
  await row.getByRole('button', { name: '下载 CSV' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('monthly-2026-09.csv')
  await expect(page.getByText('报表 CSV 已导出。')).toBeVisible()
})

const failedJob = {
  id: 801,
  status: 'failed',
  model_name: 'moving-average',
  model_version: 'v1.0',
  horizon: 7,
  window_size: 3,
  input_start_date: '2026-09-01',
  input_end_date: '2026-09-07',
  attempt_count: 2,
  error_message: '预测服务超时，请稍后重试',
  started_at: '2026-09-28 09:00:00',
  finished_at: '2026-09-28 09:00:30',
  created_at: '2026-09-28 09:00:00',
}

const emptyEvaluation = { evaluated_points: 0, mae: '0', rmse: '0', mean_deviation: '0' }

test('预测失败任务展示原因并重试成功', async ({ page }) => {
  let retryRequested = false

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/forecast/cashflow/latest', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ job: failedJob, results: [], evaluation: emptyEvaluation }),
    }),
  )
  await page.route('**/api/v1/forecast/models', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok([]) }),
  )
  await page.route('**/api/v1/forecast/cashflow/jobs/801/retry', async (route) => {
    retryRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        job: {
          ...failedJob,
          status: 'running',
          attempt_count: 3,
          error_message: null,
          finished_at: null,
        },
        results: [],
        evaluation: emptyEvaluation,
      }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '现金预测' }).click()
  await expect(page.getByRole('heading', { name: '现金流预测工作台' })).toBeVisible()
  await expect(page.getByText('预测服务超时，请稍后重试')).toBeVisible()
  await expect(page.getByText('任务 #801 · failed · v1.0')).toBeVisible()
  await expect(page.getByText('尝试次数')).toBeVisible()

  await page.getByRole('button', { name: '重试失败任务' }).click()
  await expect(page.getByText('失败任务已重试。')).toBeVisible()
  expect(retryRequested).toBe(true)
  await expect(page.getByText('任务 #801 · running · v1.0')).toBeVisible()
  await expect(page.getByText('预测服务超时，请稍后重试')).toBeHidden()
})
