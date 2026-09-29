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
  'receipt:view',
  'receipt:import',
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

  const statementPanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '导入流水', exact: true }),
  })
  await statementPanel.locator('input[type="file"]').setInputFiles({
    name: 'statement.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('交易日期,金额\n2026-09-01,100.00\n'),
  })
  await statementPanel.getByRole('button', { name: '预览导入' }).click()
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
  await page.route('**/api/v1/reports/701/download?**', async (route) =>
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

test('流水高级筛选、批量分类和 Excel 导出', async ({ page }) => {
  let lastListUrl = ''
  let batchClassified: Record<string, unknown> | null = null
  const transactionsPage = {
    items: [
      {
        id: 501,
        bank_account_id: 1,
        transaction_no: 'TXN-501',
        transaction_date: '2026-09-01',
        direction: 'income',
        amount: '500.00',
        balance_after: '1000.00',
        counterparty_name: '示例客户',
        summary: '货款',
        purpose: null,
        category: null,
        match_status: 'unmatched',
        bank_name: '工商银行',
        account_no_last4: '1234',
      },
    ],
    page: 1,
    page_size: 20,
    total: 1,
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
            current_balance: '100.00',
          },
        ],
        page: 1,
        page_size: 20,
        total: 1,
      }),
    }),
  )
  await page.route('**/api/v1/bank-transactions?**', async (route) => {
    lastListUrl = route.request().url()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(transactionsPage),
    })
  })
  await page.route('**/api/v1/bank-transactions/batch-classify', async (route) => {
    batchClassified = postBody(route)
    await route.fulfill({ status: 200, contentType: 'application/json', body: ok({ updated: 1 }) })
  })
  await page.route('**/api/v1/bank-transactions/export?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      body: Buffer.from('PK\x03\x04fake-xlsx'),
    }),
  )

  await login(page)
  await page.getByRole('link', { name: '银行流水' }).click()
  await expect(page.getByRole('heading', { name: '流水列表' })).toBeVisible()
  await expect(page.getByText('工商银行·1234')).toBeVisible()

  await page.getByLabel('关键字').fill('示例客户')
  await page.getByLabel('金额下限').fill('100')
  await page.getByRole('button', { name: '查询' }).click()
  await expect.poll(() => lastListUrl).toContain('keyword=%E7%A4%BA%E4%BE%8B%E5%AE%A2%E6%88%B7')
  expect(lastListUrl).toContain('amount_min=100')

  await page.locator('input[type="checkbox"]').first().check()
  await page.getByLabel('分类', { exact: true }).fill('客户回款')
  await page.getByRole('button', { name: '批量分类' }).click()
  await expect(page.getByText('已批量分类 1 条流水。')).toBeVisible()
  expect(batchClassified).toMatchObject({ transaction_ids: [501], category: '客户回款' })

  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Excel' }).click()
  expect((await download).suggestedFilename()).toBe('bank-transactions.xlsx')
})

test('财务对账展示跨月时间差和财务记录科目', async ({ page }) => {
  let runRequested = false

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/reconciliation/results?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        items: [
          {
            id: 601,
            exception_no: 'EX-REC-601',
            exception_type: 'timing_difference',
            source_type: 'finance_record',
            source_id: 61,
            title: '跨月时间差/在途：FR-601',
            description: '财务单据与银行流水金额方向一致但跨月',
            status: 'new',
            severity: 'medium',
            transaction_no: null,
            transaction_date: null,
            bank_amount: null,
            record_no: 'FR-601',
            record_date: '2026-08-25',
            finance_amount: '700.00',
            finance_subject: '应收账款',
          },
        ],
        page: 1,
        page_size: 20,
        total: 1,
      }),
    }),
  )
  await page.route('**/api/v1/reconciliation/run?**', async (route) => {
    runRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        job_id: 610,
        status: 'success',
        matched: 3,
        multi_matched: 1,
        timing_difference: 1,
        bank_unrecorded: 0,
        finance_unmatched: 0,
        date_from: '2026-08-01',
        date_to: '2026-09-30',
      }),
    })
  })
  await page.route('**/api/v1/finance-records?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        items: [
          {
            id: 61,
            record_no: 'FR-601',
            record_type: 'receipt',
            record_date: '2026-08-25',
            posting_date: null,
            counterparty_name: '示例客户',
            amount: '700.00',
            summary: '货款',
            source_system: 'ERP',
            subject: '应收账款',
            status: 'active',
          },
        ],
        page: 1,
        page_size: 20,
        total: 1,
      }),
    }),
  )

  await login(page)
  await page.getByRole('link', { name: '财务对账' }).click()
  await expect(page.getByRole('heading', { name: '银行账 / 财务账对账' })).toBeVisible()
  await expect(page.getByText('跨月时间差/在途：FR-601')).toBeVisible()
  await expect(page.getByText('应收账款').first()).toBeVisible()

  await page.getByRole('button', { name: '运行对账' }).click()
  await expect(page.getByText(/多对多匹配 1 组，跨月时间差 1 条/)).toBeVisible()
  expect(runRequested).toBe(true)
})

test('规则中心修改规则并回滚版本', async ({ page }) => {
  let ruleUpdated: Record<string, unknown> | null = null
  let rollbackBody: Record<string, unknown> | null = null
  const ruleList = [
    {
      id: 1,
      rule_code: 'overdue_exists',
      threshold: '0',
      penalty: '30',
      max_penalty: null,
      enabled: true,
      updated_at: '2026-09-01 10:00:00',
    },
  ]
  const versionList = [
    {
      id: 2,
      rule_code: 'overdue_exists',
      threshold: '1',
      penalty: '20',
      max_penalty: null,
      enabled: true,
      version_no: 2,
      change_source: 'manual',
      remark: null,
      updated_by: 1,
      created_at: '2026-09-28 10:00:00',
    },
    {
      id: 1,
      rule_code: 'overdue_exists',
      threshold: '0',
      penalty: '30',
      max_penalty: null,
      enabled: true,
      version_no: 1,
      change_source: 'baseline',
      remark: '规则基线',
      updated_by: 1,
      created_at: '2026-09-01 10:00:00',
    },
  ]

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/rules/risk-rules', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(ruleList) }),
  )
  await page.route('**/api/v1/rules/config', async (route) => {
    if (route.request().method() === 'PUT') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: ok({
          match_scan_window_days: 30,
          match_exact_window_days: 7,
          match_suggest_window_days: 14,
          industry_template: 'it_software',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        match_scan_window_days: 30,
        match_exact_window_days: 7,
        match_suggest_window_days: 14,
        industry_template: 'it_software',
      }),
    })
  })
  await page.route('**/api/v1/rules/risk-rules/overdue_exists', async (route) => {
    ruleUpdated = postBody(route)
    await route.fulfill({ status: 200, contentType: 'application/json', body: ok(ruleList[0]) })
  })
  await page.route('**/api/v1/rules/risk-rules/overdue_exists/versions', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(versionList) }),
  )
  await page.route('**/api/v1/rules/risk-rules/overdue_exists/rollback', async (route) => {
    rollbackBody = postBody(route)
    await route.fulfill({ status: 200, contentType: 'application/json', body: ok(ruleList[0]) })
  })

  await login(page)
  await page.getByRole('link', { name: '规则中心' }).click()
  await expect(page.getByRole('heading', { name: '规则配置与版本管理' })).toBeVisible()
  await expect(page.getByText('当前行业模板：it_software')).toBeVisible()

  const rulePanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '项目风险规则', exact: true }),
  })
  await rulePanel.locator('input[type="number"]').first().fill('1')
  await rulePanel.getByRole('button', { name: '保存' }).first().click()
  await expect(page.getByText(/已保存并记录新版本/)).toBeVisible()
  expect(ruleUpdated).toMatchObject({ threshold: 1 })

  await page.getByRole('button', { name: '版本' }).click()
  await expect(page.getByRole('heading', { name: '版本历史：overdue_exists' })).toBeVisible()
  await expect(page.getByText('v1')).toBeVisible()
  await page.getByRole('button', { name: '回滚到此版本' }).first().click()
  await expect(page.getByText(/已回滚到版本 v2/)).toBeVisible()
  expect(rollbackBody).toMatchObject({ version_no: 2 })
})

test('异常工作台统计、队列和责任人筛选', async ({ page }) => {
  let lastListUrl = ''

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/matching/exceptions/stats', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        active_count: 2,
        unassigned_count: 1,
        my_todo_count: 1,
        pending_close_count: 1,
        overdue_count: 2,
        closed_count: 3,
        false_positive_count: 0,
        total_count: 6,
        closure_rate: 0.5,
        avg_resolution_hours: 12.5,
        by_type: [{ exception_type: 'unknown_receipt', count: 2 }],
        by_owner: [
          {
            owner_user_id: 1,
            owner_name: '财务负责人',
            total: 3,
            active: 1,
            resolved_closed: 2,
            overdue: 1,
          },
        ],
      }),
    }),
  )
  await page.route('**/api/v1/matching/exceptions?**', async (route) => {
    lastListUrl = route.request().url()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        items: [
          {
            id: 701,
            exception_no: 'EX-701',
            exception_type: 'unknown_receipt',
            title: '未知收款：示例客户',
            description: '到账流水未匹配到合同应收计划',
            status: 'in_progress',
            stage: '处理中',
            severity: 'medium',
            due_date: '2026-09-20',
            owner_user_id: 1,
            owner_name: '财务负责人',
          },
        ],
        page: 1,
        page_size: 20,
        total: 1,
      }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '异常事项' }).click()
  await expect(page.getByRole('heading', { name: '异常处理工作台' })).toBeVisible()
  await expect(page.locator('.summary-grid').getByText('我的待办')).toBeVisible()
  await expect(page.getByText('12.5h')).toBeVisible()
  await expect(page.getByText('待处理 1 · 已处理 2 · 超期 1')).toBeVisible()
  await expect(page.getByText('处理中')).toBeVisible()
  await expect(page.getByText('财务负责人').first()).toBeVisible()

  await page.getByLabel('队列').selectOption('mine')
  await expect.poll(() => lastListUrl).toContain('queue=mine')
  expect(lastListUrl).not.toContain('active_only')

  await page.getByLabel('责任人').selectOption('1')
  await expect.poll(() => lastListUrl).toContain('owner_user_id=1')
})

test('生成周报并下载 Excel 与打印预览', async ({ page }) => {
  let createBody: Record<string, unknown> | null = null
  const weeklyTask = {
    id: 901,
    report_type: 'weekly',
    date_from: '2026-09-07',
    date_to: '2026-09-13',
    status: 'success',
    file_name: 'weekly-cash-report-20260907-20260913.csv',
    error_message: null,
    created_at: '2026-09-14 08:00:00',
    updated_at: '2026-09-14 08:00:01',
    result: {
      report_name: '资金周报',
      report_type: 'weekly',
      income_total: '1000.00',
      expense_total: '300.00',
      net_cashflow: '700.00',
      receivable_due_in_week: '2000.00',
      confirmed_receipts_in_week: '800.00',
      active_accounts_in_week: 2,
      exceptions_closed_in_week: 1,
    },
  }

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/reports?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ items: [weeklyTask], page: 1, page_size: 20, total: 1 }),
    }),
  )
  await page.route('**/api/v1/reports', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    createBody = postBody(route)
    await route.fulfill({ status: 200, contentType: 'application/json', body: ok(weeklyTask) })
  })
  await page.route('**/api/v1/reports/901/download?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      body: Buffer.from('PK\x03\x04fake'),
    }),
  )

  await login(page)
  await page.getByRole('link', { name: '报表中心' }).click()
  await expect(page.getByRole('heading', { name: '资金经营报告' })).toBeVisible()
  await expect(page.locator('td', { hasText: '周报' })).toBeVisible()

  await page.locator('select').first().selectOption('weekly')
  await page.getByLabel('周内任意日期').fill('2026-09-10')
  await page.getByRole('button', { name: '生成报表' }).click()
  await expect(page.getByText('报表已生成。')).toBeVisible()
  expect(createBody).toMatchObject({
    report_type: 'weekly',
    params_json: { week: '2026-09-10' },
  })

  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 Excel' }).click()
  expect((await download).suggestedFilename()).toBe('weekly-cash-report-20260907-20260913.xlsx')
  await expect(page.getByRole('button', { name: '打印/PDF' })).toBeVisible()
})
