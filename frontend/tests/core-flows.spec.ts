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
  'system:manage',
  'audit:view',
  'template:manage',
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
  await page.getByRole('link', { name: '流水查询' }).click()
  await expect(page.getByRole('heading', { name: '流水列表' })).toBeVisible()

  await page.getByRole('button', { name: '导入流水' }).click()
  const statementPanel = page.getByRole('dialog', { name: '导入流水' })
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

  await statementPanel.getByRole('button', { name: '确认入账' }).click()
  await expect(statementPanel.getByText('已确认入账。')).toBeVisible()
  expect(confirmRequested).toBe(true)
  await expect(statementPanel.getByRole('button', { name: '关闭', exact: true })).toBeVisible()
})

test('待核销工单确认与打回', async ({ page }) => {
  const state = { confirmed: false, rejected: false }
  let confirmRequested = false
  let rejectReason = ''

  const carrierItem = (
    id: number,
    excNo: string,
    resultId: number,
    txnNo: string,
    matchStatus: string,
  ) => ({
    id,
    exception_no: excNo,
    exception_type: 'partial_receipt',
    title: '部分收款：软件实施合同',
    description: '待人工确认',
    status: matchStatus === 'rejected' ? 'in_progress' : 'new',
    severity: 'high',
    due_date: '2026-09-30',
    owner_user_id: null,
    biz_amount: '100.00',
    match_group_id: `MG-${id}`,
    match_summary: {
      match_group_id: `MG-${id}`,
      match_status: matchStatus,
      confidence: 'medium',
      txn_no: txnNo,
      amount: '100.00',
      contract_no: 'CT-1',
      result_id: resultId,
      counterparty_name: '甲方科技',
      summary: '首付款',
      node_name: '首付款',
    },
  })

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/matching/exceptions?**', async (route) => {
    const items = [
      ...(state.confirmed ? [] : [carrierItem(701, 'EXC-7001', 501, 'TXN-9001', 'suggested')]),
      carrierItem(702, 'EXC-7002', 502, 'TXN-9002', state.rejected ? 'rejected' : 'suggested'),
    ]
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ items, page: 1, page_size: 20, total: items.length }),
    })
  })
  await page.route('**/api/v1/matching/results/501/confirm', async (route) => {
    confirmRequested = true
    state.confirmed = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ id: 501, match_status: 'confirmed' }),
    })
  })
  await page.route('**/api/v1/matching/results/502/reject', async (route) => {
    state.rejected = true
    rejectReason = String(postBody(route).reason ?? '')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ id: 502, match_status: 'rejected' }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '待办处理' }).click()
  await expect(page.getByRole('heading', { name: '待办处理' })).toBeVisible()

  // 行内确认核销：一键完成，行从队列消失
  const confirmRow = page.locator('.dense-table tbody tr', { hasText: 'EXC-7001' })
  await confirmRow.getByRole('button', { name: '确认核销' }).click()
  await expect(page.getByText('已确认核销。')).toBeVisible()
  expect(confirmRequested).toBe(true)
  await expect(page.locator('.dense-table tbody tr', { hasText: 'EXC-7001' })).toHaveCount(0)

  // 行内打回：必填原因，工单留在队列转入待跟进
  const rejectRow = page.locator('.dense-table tbody tr', { hasText: 'EXC-7002' })
  await rejectRow.getByRole('button', { name: '打回' }).click()
  const rejectDialog = page.getByRole('dialog', { name: '打回：EXC-7002' })
  await rejectDialog.getByLabel('处理说明').fill('付款方待核实')
  await rejectDialog.getByRole('button', { name: '确认', exact: true }).click()
  await expect(rejectDialog.getByText('已打回，工单转入待跟进。')).toBeVisible()
  expect(rejectReason).toBe('付款方待核实')
  await rejectDialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.locator('.dense-table tbody tr', { hasText: 'EXC-7002' })).toHaveCount(1)
  await expect(
    page.locator('.dense-table tbody tr', { hasText: 'EXC-7002' }).locator('.pill'),
  ).toHaveText('处理中')
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
  await page.route('**/api/v1/matching/exceptions/601', async (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        exception: exceptionCase(state.resolved ? 'resolved' : 'new'),
        logs: [
          {
            id: 1,
            action_type: 'COMMENT',
            action_text: '已联系客户确认回款时间',
            action_at: '2026-09-29 11:00:00',
          },
        ],
        attachments: [],
        source: [],
      }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '待办处理' }).click()
  await expect(page.getByRole('heading', { name: '待办处理' })).toBeVisible()

  const row = page.locator('.dense-table tbody tr', { hasText: 'EXC-0001' })
  await expect(row.getByText('未知收款待确认')).toBeVisible()
  await expect(row.locator('.pill')).toHaveText('新发现')

  await row.getByRole('button', { name: '备注' }).click()
  const commentDialog = page.getByRole('dialog', { name: '备注：EXC-0001' })
  await commentDialog.getByLabel('处理说明').fill('已联系客户确认回款时间')
  await commentDialog.getByRole('button', { name: '确认', exact: true }).click()
  await expect(commentDialog.getByText('异常事项操作已完成。')).toBeVisible()
  expect(commentText).toBe('已联系客户确认回款时间')
  await commentDialog.getByRole('button', { name: '关闭', exact: true }).click()

  await row.getByRole('button', { name: '处理完成' }).click()
  const resolveDialog = page.getByRole('dialog', { name: '处理完成：EXC-0001' })
  await resolveDialog.getByLabel('处理说明').fill('客户已回款，处理完成')
  await resolveDialog.getByRole('button', { name: '确认', exact: true }).click()
  await expect.poll(() => resolveText).toBe('客户已回款，处理完成')
  await resolveDialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(row.locator('.pill')).toHaveText('已解决')
  await expect(row.getByRole('button', { name: '关闭' })).toBeVisible()

  await row.getByRole('button', { name: '详情' }).click()
  const exceptionDialog = page.getByRole('dialog', { name: /这笔款怎么回事/ })
  await expect(exceptionDialog.getByText('标题')).toBeVisible()
  await expect(exceptionDialog.getByText('严重级别')).toBeVisible()
  await expect(exceptionDialog.getByText('处理时间轴')).toBeVisible()
  await expect(
    exceptionDialog.locator('.timeline').getByText('备注', { exact: true }),
  ).toBeVisible()
  await expect(exceptionDialog.getByText('已联系客户确认回款时间')).toBeVisible()
  await exceptionDialog.locator('footer').getByRole('button', { name: '关闭', exact: true }).click()
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
  await page.getByRole('link', { name: '资金报表' }).click()
  await expect(page.getByRole('heading', { name: '资金经营报告' })).toBeVisible()

  await page.getByRole('button', { name: '生成报告' }).click()
  const genDialog = page.getByRole('dialog', { name: '生成报告' })
  await genDialog.getByRole('button', { name: '生成报表' }).click()
  await expect(genDialog.getByText('报表已生成。')).toBeVisible()
  expect(createBody.report_type).toBe('monthly')
  expect(String((createBody.params_json as Record<string, string>)?.month ?? '')).toMatch(
    /^\d{4}-\d{2}$/,
  )
  await genDialog.getByRole('button', { name: '关闭', exact: true }).click()

  await page.getByRole('button', { name: '报表任务' }).click()
  const taskDialog = page.getByRole('dialog', { name: '报表任务' })
  const row = taskDialog.locator('tr', { hasText: '月报' })
  await expect(row.locator('.pill')).toHaveText('成功')

  const downloadPromise = page.waitForEvent('download')
  await row.getByRole('button', { name: '下载 CSV' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('monthly-2026-09.csv')
  await expect(taskDialog.getByText('报表 CSV 已导出。')).toBeVisible()
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
  await page.getByRole('link', { name: '现金流预测' }).click()
  await expect(page.getByRole('heading', { name: '现金流预测工作台' })).toBeVisible()
  await expect(page.getByText('预测服务超时，请稍后重试')).toBeVisible()
  await expect(page.getByText('任务 #801 · 失败 · v1.0')).toBeVisible()
  await expect(page.getByText('尝试次数')).toBeVisible()

  await page.getByRole('button', { name: '重试失败任务' }).click()
  await expect(page.getByText('失败任务已重试。')).toBeVisible()
  expect(retryRequested).toBe(true)
  await expect(page.getByText('任务 #801 · 处理中 · v1.0')).toBeVisible()
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
  await page.getByRole('link', { name: '流水查询' }).click()
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
  await page.getByRole('button', { name: '对账', exact: true }).click()
  const reconDialog = page.getByRole('dialog', { name: '银行账 / 财务账对账' })
  await expect(reconDialog.getByText('跨月时间差/在途：FR-601')).toBeVisible()
  await expect(reconDialog.getByText('应收账款').first()).toBeVisible()

  await reconDialog.getByRole('button', { name: '运行对账' }).click()
  await expect(reconDialog.getByText(/多对多匹配 1 组，跨月时间差 1 条/)).toBeVisible()
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
  await expect(page.getByText('当前行业模板：IT 软件与信息服务')).toBeVisible()
  await expect(page.locator('select').filter({ hasText: '制造业' }).first()).toBeVisible()

  await page.getByRole('button', { name: '项目风险规则' }).click()
  const rulePanel = page.getByRole('dialog', { name: '项目风险规则' })
  await expect(rulePanel.getByText('存在逾期应收', { exact: true })).toBeVisible()
  await expect(rulePanel.locator('td').getByText('overdue_exists', { exact: true })).toHaveCount(0)
  const helpButton = rulePanel.getByRole('button', { name: '查看存在逾期应收的说明与用法' })
  await expect(helpButton).toBeVisible()
  await helpButton.click()
  await expect(rulePanel.getByText(/监控项目逾期未收金额/)).toBeVisible()
  await expect(rulePanel.getByText(/字段标识：overdue_exists/)).toBeVisible()
  await helpButton.click()
  await expect(rulePanel.getByText(/监控项目逾期未收金额/)).toHaveCount(0)
  await rulePanel.locator('input[type="number"]').first().fill('1')
  await rulePanel.getByRole('button', { name: '保存' }).first().click()
  await expect(page.getByText(/已保存并记录新版本/)).toBeVisible()
  expect(ruleUpdated).toMatchObject({ threshold: 1 })

  await page.getByRole('button', { name: '版本' }).click()
  await expect(
    page.getByRole('heading', { name: '版本历史：存在逾期应收（overdue_exists）' }),
  ).toBeVisible()
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
        today_new_count: 2,
        review_pending_count: 1,
        large_amount_count: 1,
        large_amount_threshold: 100000,
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
  await page.getByRole('link', { name: '待办处理' }).click()
  await expect(page.getByRole('heading', { name: '待办处理' })).toBeVisible()
  await expect(page.locator('.summary-grid').getByText('我的待办')).toBeVisible()
  await expect(page.locator('.summary-grid').getByText('今日新增')).toBeVisible()
  await expect(page.locator('.summary-grid').getByText('待复核')).toBeVisible()
  await expect(page.locator('.summary-grid').getByText('大额待办')).toBeVisible()
  await expect(page.getByText('12.5h')).toBeVisible()
  await expect(page.getByText('待处理 1 · 已处理 2 · 超期 1')).toBeVisible()
  await expect(page.getByText('处理中')).toBeVisible()
  await expect(page.getByText('财务负责人').first()).toBeVisible()

  await page.getByRole('tab', { name: '待分派' }).click()
  await expect.poll(() => lastListUrl).toContain('queue=unassigned')
  expect(lastListUrl).not.toContain('active_only')

  await page.getByRole('tab', { name: '全部记录' }).click()
  await expect.poll(() => lastListUrl).toContain('active_only=false')

  await page.getByRole('button', { name: '待认领', exact: true }).click()
  await expect.poll(() => lastListUrl).toContain('exception_type=unknown_receipt')

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
  await page.getByRole('link', { name: '资金报表' }).click()
  await expect(page.getByRole('heading', { name: '资金经营报告' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '最新周报' })).toBeVisible()
  await expect(page.getByText('资金周报').first()).toBeVisible()

  await page.getByRole('button', { name: '生成报告' }).click()
  const genDialog = page.getByRole('dialog', { name: '生成报告' })
  await genDialog.locator('select').first().selectOption('weekly')
  await genDialog.getByLabel('周内任意日期').fill('2026-09-10')
  await genDialog.getByRole('button', { name: '生成报表' }).click()
  await expect(genDialog.getByText('报表已生成。')).toBeVisible()
  expect(createBody).toMatchObject({
    report_type: 'weekly',
    params_json: { week: '2026-09-10' },
  })
  await genDialog.getByRole('button', { name: '关闭', exact: true }).click()

  await page.getByRole('button', { name: '报表任务' }).click()
  const taskDialog = page.getByRole('dialog', { name: '报表任务' })
  await expect(taskDialog.locator('td', { hasText: '周报' })).toBeVisible()

  const download = page.waitForEvent('download')
  await taskDialog.getByRole('button', { name: '下载 Excel' }).click()
  expect((await download).suggestedFilename()).toBe('weekly-cash-report-20260907-20260913.xlsx')
  await expect(taskDialog.getByRole('button', { name: '打印/PDF' })).toBeVisible()
})

test('项目负责人筛选与详情付款事实/资金时间线', async ({ page }) => {
  let lastListUrl = ''
  const project = {
    id: 501,
    project_no: 'PJ-501',
    project_name: '示例项目',
    customer_name: '示例客户',
    project_manager: '张三',
    project_status: 'active',
    start_date: '2026-09-01',
    delivery_date: null,
    acceptance_date: null,
    remark: null,
    contract_amount: '1000.00',
    receivable_amount: '1000.00',
    paid_amount: '500.00',
    overdue_amount: '0',
    contract_count: 1,
    exception_count: 0,
    paid_rate: 0.5,
    risk_score: 90,
    risk_level: 'healthy',
    risk_items: [],
  }

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/projects?**', async (route) => {
    lastListUrl = route.request().url()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ items: [project], page: 1, page_size: 20, total: 1 }),
    })
  })
  await page.route('**/api/v1/projects/risk-rules', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok([]) }),
  )
  await page.route('**/api/v1/projects/501', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        project,
        summary: { paid_out_amount: '200.00', risk_items: [] },
        contracts: [],
        receivables: [],
        transactions: [],
        payments: [
          {
            id: 91,
            transaction_no: 'TX-PAY-91',
            transaction_date: '2026-09-15',
            direction: 'expense',
            allocated_amount: '200.00',
            counterparty_name: '供应商甲',
            summary: '外包付款',
          },
        ],
        timeline: [
          {
            date: '2026-12-01',
            type: 'receivable',
            title: '应收节点：验收款',
            amount: '1000.00',
            status: 'unpaid',
          },
          {
            date: '2026-09-15',
            type: 'payment',
            title: '供应商甲 外包付款',
            amount: '200.00',
            status: 'matched',
          },
          { date: '2026-09-01', type: 'milestone', title: '项目启动', amount: null, status: null },
        ],
        exceptions: [],
      }),
    }),
  )

  await login(page)
  await page.getByRole('link', { name: '项目资金' }).click()
  await expect(page.getByRole('heading', { name: '项目资金与风险' })).toBeVisible()

  await page.getByLabel('负责人').fill('张三')
  await page.getByRole('button', { name: '查询' }).click()
  await expect.poll(() => lastListUrl).toContain('project_manager')

  await page.getByRole('button', { name: '详情' }).click()
  const projectDialog = page.getByRole('dialog', { name: '项目详情' })
  await expect(page.getByRole('heading', { name: '项目详情' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '付款事实' })).toBeVisible()
  await expect(page.getByText('供应商甲')).toBeVisible()
  await expect(page.getByRole('heading', { name: '资金时间线' })).toBeVisible()
  await expect(page.getByText('项目启动')).toBeVisible()
  await expect(page.getByText('应收节点：验收款')).toBeVisible()
  await expect(projectDialog.getByText('项目经理')).toBeVisible()
  await expect(projectDialog.getByText('项目状态')).toBeVisible()
  await expect(projectDialog.getByText('进行中')).toBeVisible()
  await expect(projectDialog.getByText('流出金额')).toBeVisible()
  await expect(projectDialog.getByText('未收款')).toBeVisible()
})

test('系统管理新建用户与角色权限编辑', async ({ page }) => {
  let createBody: Record<string, unknown> | null = null
  let permissionBody: Record<string, unknown> | null = null

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/system/users?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        items: [
          {
            id: 1,
            login_name: 'admin01',
            display_name: '系统管理员',
            status: 'active',
            last_login_at: null,
            created_at: '2026-09-01 10:00:00',
            roles: ['ADMIN'],
            scoped_project_count: 0,
          },
        ],
        page: 1,
        page_size: 50,
        total: 1,
      }),
    }),
  )
  await page.route('**/api/v1/system/users', async (route) => {
    createBody = postBody(route)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ id: 2, login_name: 'cashier02', display_name: '出纳乙', status: 'active' }),
    })
  })
  await page.route('**/api/v1/system/roles', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok([
        {
          id: 5,
          role_code: 'CASHIER',
          role_name: '出纳资金专员',
          status: 'active',
          created_at: '2026-09-01 10:00:00',
          permission_codes: ['dashboard:view'],
        },
      ]),
    }),
  )
  await page.route('**/api/v1/system/permissions', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok([
        { permission_code: 'dashboard:view', permission_name: '查看驾驶舱', module: 'dashboard' },
        { permission_code: 'report:download', permission_name: '下载报表', module: 'report' },
      ]),
    }),
  )
  await page.route('**/api/v1/system/roles/5/permissions', async (route) => {
    permissionBody = postBody(route)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ id: 5, role_code: 'CASHIER', role_name: '出纳资金专员', status: 'active' }),
    })
  })
  await page.route('**/api/v1/projects?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(EMPTY_PAGE) }),
  )

  await login(page)
  await page.getByRole('link', { name: '用户与权限' }).click()
  await expect(page.getByRole('heading', { name: '用户、角色与数据范围' })).toBeVisible()
  await expect(page.getByText('admin01')).toBeVisible()

  await page.getByRole('button', { name: '新建用户' }).click()
  const userDialog = page.getByRole('dialog', { name: '新建用户' })
  await userDialog.getByLabel('登录名').fill('cashier02')
  await userDialog.getByLabel('姓名').fill('出纳乙')
  await userDialog.getByLabel('密码').fill('Passw0rd!23')
  await userDialog.getByLabel('角色').selectOption('CASHIER')
  await userDialog.getByRole('button', { name: '确认新建' }).click()
  await expect(userDialog.getByText('用户 cashier02 已创建。')).toBeVisible()
  expect(createBody).toMatchObject({ login_name: 'cashier02', role_codes: ['CASHIER'] })
  await expect(userDialog.getByRole('button', { name: '关闭', exact: true })).toBeVisible()
  await userDialog.getByRole('button', { name: '关闭', exact: true }).click()

  await page.getByRole('button', { name: '编辑权限' }).click()
  const roleDialog = page.getByRole('dialog', { name: '编辑角色权限：出纳资金专员' })
  await expect(
    roleDialog.getByRole('heading', { name: '编辑角色权限：出纳资金专员' }),
  ).toBeVisible()
  await roleDialog.getByLabel(/下载报表/).check()
  await roleDialog.getByRole('button', { name: '保存权限' }).click()
  await expect(roleDialog.getByText('角色 出纳资金专员 的权限已更新。')).toBeVisible()
  expect(permissionBody).toMatchObject({
    permission_codes: ['dashboard:view', 'report:download'],
  })
  await expect(roleDialog.getByRole('button', { name: '关闭', exact: true })).toBeVisible()
})

test('银行接入配置新建、模板字典与连接测试', async ({ page }) => {
  let createBody: Record<string, unknown> | null = null
  let tested = false

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
  await page.route('**/api/v1/bank-connections/templates', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok([
        {
          bank_code: 'BOC',
          bank_name: '中国银行',
          format: 'xlsx/csv',
          recognition: '工作表名称包含银行名称 + 表头归一化映射',
          field_mappings: [{ header: '交易日期', field: 'transaction_date' }],
        },
      ]),
    }),
  )
  await page.route('**/api/v1/bank-connections', async (route) => {
    if (route.request().method() === 'POST') {
      createBody = postBody(route)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: ok({ id: 41, bank_code: 'BOC', bank_name: '中国银行', connection_type: 'file' }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok([
        {
          id: 41,
          bank_code: 'BOC',
          bank_name: '中国银行',
          connection_type: 'file',
          bank_account_id: 1,
          account_name: '基本户',
          status: 'active',
          last_tested_at: null,
          last_test_status: null,
          last_test_message: null,
          remark: null,
          created_at: '2026-09-29 10:00:00',
        },
      ]),
    })
  })
  await page.route('**/api/v1/bank-connections/41/test', async (route) => {
    tested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        connection_id: 41,
        bank_code: 'BOC',
        status: 'success',
        message: '模板识别成功，解析出 3 条流水',
        duration_ms: 12,
        parsed_rows: 3,
        error_rows: 0,
      }),
    })
  })
  await page.route('**/api/v1/bank-accounts/1', async (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        id: 1,
        bank_code: 'ICBC',
        bank_name: '工商银行',
        account_name: '基本户',
        account_no_last4: '1234',
        currency: 'CNY',
        status: 'active',
        current_balance: '100.00',
        last_transaction_at: null,
        idle_days: null,
        idle_level: null,
      }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '银行账户管理' }).click()

  await page.getByRole('button', { name: '银行模板字典' }).click()
  const templateDialog = page.getByRole('dialog', { name: '银行模板字典' })
  await expect(templateDialog.getByText('中国银行（BOC）')).toBeVisible()
  await templateDialog.getByRole('button', { name: '关闭弹层' }).click()

  await page.getByRole('button', { name: '银行接入配置' }).click()
  const connectionDialog = page.getByRole('dialog', { name: '银行接入配置' })
  await connectionDialog.getByLabel('银行编码').fill('BOC')
  await connectionDialog.getByLabel('银行名称').fill('中国银行')
  await connectionDialog.getByRole('button', { name: '新建接入配置' }).click()
  await expect(connectionDialog.getByText('接入配置已保存。')).toBeVisible()
  expect(createBody).toMatchObject({ bank_code: 'BOC', bank_name: '中国银行' })
  await expect(connectionDialog.getByRole('button', { name: '关闭', exact: true })).toBeVisible()

  await connectionDialog.getByText('选择样本').first().click()
  await connectionDialog
    .locator('input[type="file"]')
    .first()
    .setInputFiles({
      name: 'sample.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from('交易日期,金额\n2026-09-01,100.00\n'),
    })
  await connectionDialog.getByRole('button', { name: '连接测试' }).click()
  await expect(connectionDialog.getByText(/连接测试成功/)).toBeVisible()
  expect(tested).toBe(true)
  await connectionDialog.getByRole('button', { name: '关闭弹层' }).click()

  await page.getByRole('button', { name: '详情', exact: true }).click()
  const accountDialog = page.getByRole('dialog', { name: '账户详情' })
  await expect(accountDialog.getByText('银行代码')).toBeVisible()
  await expect(accountDialog.getByText('账号后四位')).toBeVisible()
  await expect(accountDialog.getByText('当前余额')).toBeVisible()
  await accountDialog.getByRole('button', { name: '关闭', exact: true }).click()
})

test('模板配置中心映射管理', async ({ page }) => {
  let createdBody: Record<string, unknown> | null = null
  const banks = [
    ['BOC', '中国银行'],
    ['ICBC', '工商银行'],
    ['CGB', '广发银行'],
    ['PAB', '平安银行'],
    ['BOSC', '上海银行'],
    ['SUZB', '苏州银行'],
  ] as const
  const templates = banks.map(([bank_code, bank_name]) => ({
    bank_code,
    bank_name,
    format: 'xlsx/csv',
    recognition: '工作表名称包含银行名称 + 表头归一化映射',
    field_mappings: [
      { header: '交易日期', field: 'transaction_date' },
      { header: '流水号', field: 'transaction_no' },
    ],
  }))
  const fields = [
    { field: 'transaction_no', label: '流水号' },
    { field: 'transaction_date', label: '交易日期' },
    { field: 'direction', label: '收支方向' },
    { field: 'amount', label: '交易金额' },
    { field: 'debit_amount', label: '借方金额（支出）' },
    { field: 'credit_amount', label: '贷方金额（收入）' },
    { field: 'balance_after', label: '余额' },
    { field: 'counterparty_name', label: '对方户名' },
    { field: 'summary', label: '摘要' },
    { field: 'currency', label: '币种' },
  ]
  const mappings = [
    {
      id: 2,
      header: '交易金额(元)',
      field: 'amount',
      created_at: '2026-09-20 10:00:00',
      updated_at: '2026-09-20 10:00:00',
    },
  ]

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/bank-templates', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ templates, fields, mappings }),
    }),
  )
  await page.route('**/api/v1/bank-templates/mappings', async (route) => {
    createdBody = postBody(route)
    mappings.unshift({
      id: 3,
      header: String(createdBody.header),
      field: String(createdBody.field),
      created_at: '2026-10-08 10:00:00',
      updated_at: '2026-10-08 10:00:00',
    })
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(mappings[0]),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '导入模板配置' }).click()
  await expect(page.getByRole('heading', { name: '银行模板与表头映射' })).toBeVisible()

  const bankPanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '内置银行模板', exact: true }),
  })
  await expect(bankPanel.locator('.mini-row')).toHaveCount(6)
  await expect(bankPanel.getByText('中国银行（BOC）')).toBeVisible()
  await expect(bankPanel.getByText('苏州银行（SUZB）')).toBeVisible()
  await expect(bankPanel.getByRole('img', { name: '中国银行图标' })).toBeVisible()
  await expect(bankPanel.getByRole('img', { name: '苏州银行图标' })).toBeVisible()

  await bankPanel.getByRole('button', { name: '字段映射' }).first().click()
  const bankMappingDialog = page.getByRole('dialog', { name: '中国银行 字段映射' })
  await expect(bankMappingDialog).toBeVisible()
  await expect(bankMappingDialog.getByRole('columnheader', { name: '银行表头' })).toBeVisible()
  await expect(bankMappingDialog.getByRole('columnheader', { name: '标准字段' })).toBeVisible()
  await expect(bankMappingDialog.locator('tbody tr')).toHaveCount(2)
  await bankMappingDialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(bankMappingDialog).toBeHidden()

  const mappingPanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '自定义表头映射', exact: true }),
  })
  await expect(mappingPanel.getByText('交易金额(元)')).toBeVisible()
  await expect(mappingPanel.getByText('交易金额', { exact: true })).toBeVisible()

  await mappingPanel.getByRole('button', { name: '新增映射' }).click()
  const mappingDialog = page.getByRole('dialog', { name: '新增表头映射' })
  await mappingDialog.getByLabel('源表头').fill('交易流水编号')
  await mappingDialog.getByLabel('标准字段').selectOption('transaction_no')
  await mappingDialog.getByRole('button', { name: '新增映射' }).click()

  await expect(page.getByText(/表头映射「交易流水编号」已新增/)).toBeVisible()
  await expect(mappingPanel.getByText('交易流水编号')).toBeVisible()
  await expect(mappingPanel.getByText('流水号', { exact: true })).toBeVisible()
  expect(createdBody).toMatchObject({ header: '交易流水编号', field: 'transaction_no' })
})

test('驾驶舱试点验收指标与快照采集', async ({ page }) => {
  let collectRequested = false
  const pilotPayload = {
    as_of: '2026-10-09',
    indicators: [
      {
        metric_code: 'import_success_rate',
        label: '银行流水导入成功率',
        value: 96.5,
        threshold: 95,
        pass: true,
      },
      {
        metric_code: 'template_recognition_rate',
        label: '模板识别成功率',
        value: 88,
        threshold: 90,
        pass: false,
      },
      {
        metric_code: 'auto_match_rate',
        label: '自动匹配率',
        value: 72.25,
        threshold: 70,
        pass: true,
      },
      {
        metric_code: 'manual_confirm_rate',
        label: '人工确认率',
        value: null,
        threshold: 80,
        pass: false,
      },
      {
        metric_code: 'exception_closure_rate',
        label: '异常闭环率',
        value: 91,
        threshold: 90,
        pass: true,
      },
      {
        metric_code: 'report_success_rate',
        label: '报表生成成功率',
        value: 100,
        threshold: 100,
        pass: true,
      },
    ],
    amounts: [
      { metric_code: 'total_balance', label: '账户总余额', value: 125000.5 },
      { metric_code: 'yesterday_net_inflow', label: '昨日净流入', value: 2300 },
      { metric_code: 'receivable_total', label: '应收总额', value: 80000 },
      { metric_code: 'received_total', label: '已收总额', value: 50000 },
      { metric_code: 'overdue_receivable', label: '逾期未收', value: 2000 },
      { metric_code: 'open_exception_count', label: '未闭环异常数', value: 3 },
      { metric_code: 'match_rate', label: '流水匹配率', value: 66.67 },
    ],
    last_snapshot_date: '2026-10-08',
  }
  let lastSnapshotDate: string | null = '2026-10-08'

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/bank-accounts?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(EMPTY_PAGE) }),
  )
  await page.route('**/api/v1/metrics/pilot-acceptance', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ ...pilotPayload, last_snapshot_date: lastSnapshotDate }),
    }),
  )
  await page.route('**/api/v1/metrics/snapshots/collect', async (route) => {
    collectRequested = true
    lastSnapshotDate = '2026-10-09'
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ snapshot_date: '2026-10-09', collected: 13 }),
    })
  })
  await page.route('**/api/v1/metrics/snapshots?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok([
        { snapshot_date: '2026-10-08', metric_value: 95.5 },
        { snapshot_date: '2026-10-09', metric_value: 96.5 },
      ]),
    }),
  )

  await login(page)
  const pilotPanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '试点验收指标', exact: true }),
  })
  await expect(pilotPanel).toBeVisible()
  await expect(pilotPanel.getByText('最近快照 2026-10-08')).toBeVisible()

  // 6 项验收指标与达标状态
  await expect(pilotPanel.locator('tbody tr')).toHaveCount(6)
  await expect(pilotPanel.getByText('银行流水导入成功率')).toBeVisible()
  await expect(pilotPanel.getByText('96.5%')).toBeVisible()
  await expect(pilotPanel.getByText('≥95%')).toBeVisible()
  await expect(pilotPanel.getByText('达标', { exact: true })).toHaveCount(4)
  await expect(pilotPanel.getByText('未达标', { exact: true })).toHaveCount(2)
  await expect(pilotPanel.getByText('暂无数据')).toBeVisible()

  // 金额/数量指标小字行
  await expect(pilotPanel.getByText(/账户总余额 125,000\.50/)).toBeVisible()
  await expect(pilotPanel.getByText(/流水匹配率 66\.67%/)).toBeVisible()
  await expect(pilotPanel.getByText(/未闭环异常数 3/)).toBeVisible()

  // 展开近 30 天趋势
  await pilotPanel.getByRole('button', { name: '近 30 天趋势' }).first().click()
  const trendRow = pilotPanel.locator('.mini-row').filter({ hasText: '2026-10-08' })
  await expect(trendRow).toBeVisible()
  await expect(trendRow.getByText('95.5%')).toBeVisible()

  // 采集今日快照后刷新区块
  await pilotPanel.getByRole('button', { name: '采集今日快照' }).click()
  await expect(pilotPanel.getByText('已采集 2026-10-09 的 13 项指标快照。')).toBeVisible()
  expect(collectRequested).toBe(true)
  await expect(pilotPanel.getByText('最近快照 2026-10-09')).toBeVisible()
})

test('现金预测选择模型版本并展示回测与预测区间', async ({ page }) => {
  let runUrl = ''
  const successJob = {
    id: 901,
    status: 'success',
    model_name: 'moving-average-with-trend',
    model_version: 'v1',
    horizon: 7,
    window_size: 3,
    input_start_date: '2026-09-01',
    input_end_date: '2026-09-07',
    attempt_count: 1,
    error_message: null,
    backtest_json: '{"mae":12.34,"rmse":15.2,"points":7}',
    started_at: '2026-10-09 09:00:00',
    finished_at: '2026-10-09 09:00:30',
    created_at: '2026-10-09 09:00:00',
  }
  const forecastResults = [
    {
      id: 1,
      forecast_date: '2026-10-10',
      forecast_amount: '100.00',
      expected_receivable: '50.00',
      projected_balance: '1100.00',
      actual_amount: null,
      deviation_amount: null,
      risk_level: 'low',
      risk_message: '现金流平稳',
      ci_lower: '90.00',
      ci_upper: '110.00',
    },
  ]
  const forecastModels = [
    {
      id: 1,
      version: 'v1',
      model_name: 'moving-average-with-trend',
      status: 'active',
      config_json: null,
      metrics_json: null,
      activated_at: '2026-10-01 00:00:00',
      created_at: '2026-10-01 00:00:00',
    },
    {
      id: 2,
      version: 'v2',
      model_name: 'weekly-seasonal-naive',
      status: 'inactive',
      config_json: null,
      metrics_json: null,
      activated_at: null,
      created_at: '2026-10-01 00:00:00',
    },
    {
      id: 3,
      version: 'v3',
      model_name: 'holt-linear',
      status: 'inactive',
      config_json: null,
      metrics_json: null,
      activated_at: null,
      created_at: '2026-10-01 00:00:00',
    },
  ]

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/forecast/cashflow/latest', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ job: successJob, results: forecastResults, evaluation: emptyEvaluation }),
    }),
  )
  await page.route('**/api/v1/forecast/models', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(forecastModels) }),
  )
  await page.route('**/api/v1/forecast/cashflow/jobs?**', async (route) => {
    runUrl = route.request().url()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        job: { ...successJob, model_version: 'v3' },
        results: forecastResults,
        evaluation: emptyEvaluation,
      }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '现金流预测' }).click()
  await expect(page.getByRole('heading', { name: '现金流预测工作台' })).toBeVisible()

  // 模型版本下拉：当前启用版本 + auto + 三个版本
  const versionSelect = page.getByLabel('模型版本')
  await expect(versionSelect.locator('option')).toHaveCount(5)
  const modelPanel = page.locator('.detail-section').filter({ hasText: '模型版本' })
  await expect(modelPanel.getByText('移动平均（含趋势）')).toBeVisible()
  await expect(modelPanel.getByText('周季节稳健')).toBeVisible()
  await expect(modelPanel.getByText('Holt 线性平滑')).toBeVisible()

  // 回测指标与预测区间列
  await expect(page.getByText(/回测 MAE 12\.34 · RMSE 15\.2 · 样本点数 7/)).toBeVisible()
  await expect(page.getByText('90.00 ~ 110.00')).toBeVisible()

  // 选择 auto 后生成预测，请求携带 model_version=auto
  await versionSelect.selectOption('auto')
  await page.getByRole('button', { name: '生成预测' }).click()
  await expect(page.getByText('预测任务已完成。')).toBeVisible()
  expect(runUrl).toContain('model_version=auto')
  await expect(page.getByText(/任务 #901 · 成功 · v3/)).toBeVisible()
})

test('驾驶舱资金趋势图与 7/30 天切换', async ({ page }) => {
  const requestedDays: string[] = []
  let snapshotMode = true

  function trendsPayload(days: number, withSnapshots: boolean) {
    const series = []
    const start = Date.UTC(2026, 8, 10)
    for (let i = 0; i < days; i += 1) {
      const date = new Date(start + i * 86_400_000).toISOString().slice(0, 10)
      series.push({
        date,
        income: 100 + i,
        expense: 60 + i,
        net_inflow: 40 - (i % 5) * 20,
        balance: 1000 + i * 10,
        open_exceptions: i % 3,
        match_rate: i % 2 === 0 ? 66.5 : null,
        receivable_total: withSnapshots ? (i % 2 === 0 ? 8000 + i : null) : null,
        received_total: withSnapshots ? 5000 + i : null,
        overdue_receivable: withSnapshots ? (i % 3 === 0 ? 200 + i : null) : null,
      })
    }
    return { days, start: series[0].date, end: series[days - 1].date, series }
  }

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/bank-accounts?**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok(EMPTY_PAGE) }),
  )
  await page.route('**/api/v1/metrics/pilot-acceptance', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ as_of: '2026-10-10', indicators: [], amounts: [], last_snapshot_date: null }),
    }),
  )
  await page.route('**/api/v1/dashboard/trends?**', async (route) => {
    const url = new URL(route.request().url())
    const days = url.searchParams.get('days') ?? ''
    requestedDays.push(days)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok(trendsPayload(Number(days) || 30, snapshotMode)),
    })
  })

  await login(page)
  const trendPanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '资金趋势', exact: true }),
  })
  await expect(trendPanel).toBeVisible()

  // 默认 30 天：五张 SVG 图、折线与坐标标注
  await expect(trendPanel.locator('svg')).toHaveCount(5)
  await expect(trendPanel.locator('svg path').first()).toBeVisible()
  await expect(trendPanel.getByText('收支与净流入')).toBeVisible()
  await expect(trendPanel.getByText('应收 / 逾期')).toBeVisible()
  await expect(trendPanel.getByText('2026-09-10').first()).toBeVisible()
  expect(requestedDays).toEqual(['30'])

  // 切换 7 天：重新请求；快照指标全 null 时显示采集提示，图表减为 4 张
  snapshotMode = false
  await trendPanel.getByRole('button', { name: '近 7 天' }).click()
  await expect(trendPanel.getByText('暂无快照数据，请先在试点验收区块采集快照。')).toBeVisible()
  await expect(trendPanel.locator('svg')).toHaveCount(4)
  expect(requestedDays).toEqual(['30', '7'])
})

test('驾驶舱收付区块与资金缺口提示', async ({ page }) => {
  await mockLoginAndDashboard(page)
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
        top_receivables: [
          {
            contract_name: '示例合同',
            customer_name: '示例客户',
            amount: '800.00',
            due_date: '2026-10-20',
            status: 'unpaid',
          },
        ],
        payable_top: [
          {
            id: 1,
            contract_id: 5,
            contract_no: 'HT-2026-001',
            supplier_name: '供应商甲',
            node_name: '服务费',
            due_date: '2026-10-25',
            amount: '500.00',
            status: 'unpaid',
          },
        ],
        payable_7d_amount: '500.00',
        payable_30d_amount: '2000.00',
        receivable_30d_amount: '3000.00',
        funding_gap: {
          horizon_days: 30,
          balance: '100.00',
          receivable_30d: '3000.00',
          payable_30d: '4000.00',
          gap_amount: '-900.00',
          has_gap: true,
        },
        risk_projects: [
          {
            project_no: 'PRJ-001',
            project_name: '示例项目',
            customer_name: '示例客户',
            risk_score: 45,
            risk_level: 'high',
            risk_items: ['逾期未收 2 笔'],
          },
        ],
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
      body: ok({ as_of: '2026-10-10', indicators: [], amounts: [], last_snapshot_date: null }),
    }),
  )
  await page.route('**/api/v1/dashboard/trends?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ days: 30, start: '2026-09-11', end: '2026-10-10', series: [] }),
    }),
  )

  await login(page)

  const receivablePanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '应收 Top 5', exact: true }),
  })
  await expect(receivablePanel.getByText('示例合同')).toBeVisible()
  await expect(receivablePanel.getByText(/应收日期 2026-10-20/)).toBeVisible()

  const payablePanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '未来应付 Top 5', exact: true }),
  })
  await expect(payablePanel.getByText('供应商甲')).toBeVisible()
  await expect(payablePanel.getByText(/应付日期 2026-10-25/)).toBeVisible()
  await expect(
    payablePanel.getByText(/未来 7 天应付 500\.00 · 未来 30 天应付 2,000\.00/),
  ).toBeVisible()

  const gapPanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '资金缺口', exact: true }),
  })
  await expect(gapPanel.getByText('-900.00')).toBeVisible()
  await expect(gapPanel.getByText('未来 30 天存在资金缺口')).toBeVisible()
  await expect(gapPanel.getByText(/余额 100\.00 · 应收 3,000\.00 · 应付 4,000\.00/)).toBeVisible()

  const riskPanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '风险项目排行', exact: true }),
  })
  await expect(riskPanel.getByText(/PRJ-001 · 示例项目/)).toBeVisible()
  await expect(riskPanel.getByText(/逾期未收 2 笔/)).toBeVisible()
  await expect(riskPanel.locator('.pill')).toHaveText('高')
  await expect(riskPanel.getByRole('link')).toHaveAttribute('href', '#/projects')
})

test('账户分析视图四区块渲染与 7/30 天切换', async ({ page }) => {
  const requestedDays: string[] = []

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/bank-accounts/analysis?**', async (route) => {
    const url = new URL(route.request().url())
    const days = url.searchParams.get('days') ?? ''
    requestedDays.push(days)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        activity_window_days: 90,
        activity_ranking: [
          {
            rank: 1,
            account_id: 1,
            bank_name: '工商银行',
            account_name: '基本户',
            account_no_last4: '1234',
            use_type: 'operation',
            current_balance: '1000.00',
            txn_count_90d: 12,
            txn_amount_90d: '5000.00',
            last_transaction_date: '2026-10-09',
          },
        ],
        balance_trend: {
          days: Number(days) || 30,
          start: '2026-09-11',
          end: '2026-10-10',
          account_limit: 10,
          accounts: [
            {
              account_id: 1,
              account_name: '基本户',
              bank_name: '工商银行',
              points: [
                { date: '2026-09-11', balance: 900 },
                { date: '2026-09-12', balance: 950 },
                { date: '2026-09-13', balance: 1000 },
              ],
            },
            {
              account_id: 2,
              account_name: '工资户',
              bank_name: '中国银行',
              points: [
                { date: '2026-09-11', balance: 300 },
                { date: '2026-09-12', balance: 320 },
                { date: '2026-09-13', balance: 310 },
              ],
            },
          ],
        },
        use_type_distribution: [
          { use_type: 'operation', account_count: 2, balance: '1300.00' },
          { use_type: 'unset', account_count: 1, balance: '300.00' },
        ],
        close_suggestions: [
          {
            account_id: 2,
            bank_name: '中国银行',
            account_name: '睡眠户',
            account_no_last4: '8888',
            current_balance: '10.00',
            use_type: null,
            idle_days: 200,
            reasons: ['近 180 天无动账', '余额低于 100 元'],
            suggestion: '建议销户',
          },
        ],
      }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '账户分析' }).click()
  await expect(page.getByRole('heading', { name: '账户活跃度与结构分析' })).toBeVisible()

  const rankingPanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '账户活跃度排名', exact: true }),
  })
  await expect(rankingPanel.getByText(/统计窗口：近 90 天/)).toBeVisible()
  await expect(rankingPanel.getByText('工商银行')).toBeVisible()
  await expect(rankingPanel.getByRole('cell', { name: '经营' })).toBeVisible()
  await expect(rankingPanel.getByRole('cell', { name: '12', exact: true })).toBeVisible()
  await expect(rankingPanel.getByText('2026-10-09')).toBeVisible()

  const trendPanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '余额趋势', exact: true }),
  })
  await expect(trendPanel.locator('svg')).toHaveCount(1)
  await expect(trendPanel.locator('svg path')).toHaveCount(2)
  await expect(trendPanel.getByText('工资户', { exact: true })).toBeVisible()

  const distributionPanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '账户用途分布', exact: true }),
  })
  await expect(distributionPanel.getByText('经营')).toBeVisible()
  await expect(distributionPanel.getByText('未设置')).toBeVisible()
  await expect(distributionPanel.getByText(/占比 81\.3%/)).toBeVisible()

  const suggestionPanel = page.locator('article').filter({
    has: page.getByRole('heading', { name: '销户建议', exact: true }),
  })
  await expect(suggestionPanel.getByText('睡眠户')).toBeVisible()
  await expect(suggestionPanel.getByText('近 180 天无动账')).toBeVisible()
  await expect(suggestionPanel.getByText('建议销户')).toBeVisible()
  await expect(suggestionPanel.getByRole('link', { name: '前往账户页处理' })).toHaveAttribute(
    'href',
    '#/accounts',
  )

  expect(requestedDays).toEqual(['30'])
  const analysisRequest = page.waitForRequest('**/api/v1/bank-accounts/analysis?days=7')
  await trendPanel.getByRole('button', { name: '近 7 天' }).click()
  await analysisRequest
  expect(requestedDays).toEqual(['30', '7'])
})

test('账户表单提交账户用途', async ({ page }) => {
  let createBody: Record<string, unknown> | null = null

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
            use_type: 'operation',
          },
        ],
        page: 1,
        page_size: 20,
        total: 1,
      }),
    }),
  )
  await page.route('**/api/v1/bank-connections**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok([]) }),
  )
  await page.route('**/api/v1/bank-accounts', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    createBody = postBody(route)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        id: 2,
        bank_code: 'CMB',
        bank_name: '招商银行',
        account_name: '工资户',
        account_no_last4: '7890',
        currency: 'CNY',
        status: 'active',
        current_balance: '0.00',
        use_type: 'payroll',
      }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '银行账户管理' }).click()
  await expect(page.getByRole('heading', { name: '账户盘点' })).toBeVisible()

  await page.getByRole('button', { name: '新增银行账户' }).click()
  const accountDialog = page.getByRole('dialog', { name: '新增银行账户' })
  await accountDialog.getByLabel('银行代码').fill('CMB')
  await accountDialog.getByLabel('银行名称').fill('招商银行')
  await accountDialog.getByLabel('账户名称').fill('工资户')
  await accountDialog.getByLabel('完整账号').fill('1234567890')
  await accountDialog.getByLabel('账户用途').selectOption('payroll')
  await accountDialog.getByRole('button', { name: '新增账户' }).click()

  await expect(accountDialog.getByText('账户已新增。')).toBeVisible()
  expect(createBody).toMatchObject({ bankCode: 'CMB', useType: 'payroll' })
})

test('账户待销户流转：发起销户与撤销', async ({ page }) => {
  const state = { status: 'active' }
  let proposeBody: Record<string, unknown> | null = null
  let cancelRequested = false
  const account = () => ({
    id: 1,
    bank_code: 'ICBC',
    bank_name: '工商银行',
    account_name: '基本户',
    account_no_last4: '1234',
    currency: 'CNY',
    status: state.status,
    current_balance: '100.00',
    use_type: 'operation',
  })

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/bank-accounts?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ items: [account()], page: 1, page_size: 20, total: 1 }),
    }),
  )
  await page.route('**/api/v1/bank-connections**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok([]) }),
  )
  await page.route('**/api/v1/bank-accounts/1/propose-close', async (route) => {
    proposeBody = postBody(route)
    state.status = 'pending_close'
    await route.fulfill({ status: 200, contentType: 'application/json', body: ok(account()) })
  })
  await page.route('**/api/v1/bank-accounts/1/cancel-close', async (route) => {
    cancelRequested = true
    state.status = 'active'
    await route.fulfill({ status: 200, contentType: 'application/json', body: ok(account()) })
  })

  await login(page)
  await page.getByRole('link', { name: '银行账户管理' }).click()
  const accountRow = page.locator('tr', { hasText: '基本户' })
  await expect(accountRow.locator('.pill').first()).toHaveText('活跃')

  await accountRow.getByRole('button', { name: '发起销户' }).click()
  const proposeDialog = page.getByRole('dialog', { name: '发起销户' })
  await proposeDialog.getByLabel('销户原因').fill('长期闲置且余额已清零')
  await proposeDialog.getByRole('button', { name: '提交销户申请' }).click()
  await expect(page.getByText('已发起销户，账户进入待销户状态。')).toBeVisible()
  expect(proposeBody).toEqual({ reason: '长期闲置且余额已清零' })
  await expect(accountRow.locator('.pill').first()).toHaveText('待销户')
  await expect(accountRow.getByRole('button', { name: '完成销户' })).toBeVisible()

  page.on('dialog', (dialog) => dialog.accept())
  await accountRow.getByRole('button', { name: '撤销销户' }).click()
  await expect(page.getByText('已撤销销户，账户恢复活跃。')).toBeVisible()
  expect(cancelRequested).toBe(true)
  await expect(accountRow.locator('.pill').first()).toHaveText('活跃')
})

const EXCEPTION_ASSIGNMENT_TYPES = [
  'unreceived',
  'partial_receipt',
  'unknown_receipt',
  'bank_unrecorded',
  'finance_unmatched',
  'timing_difference',
  'fund_gap',
]

test('规则中心异常分派配置保存与行业预警运行', async ({ page }) => {
  let assignmentBody: Record<string, unknown> | null = null
  let alertsRequested = false
  const assignmentItems = EXCEPTION_ASSIGNMENT_TYPES.map((type) => ({
    exception_type: type,
    owner_user_id: null,
    owner_name: null,
  }))

  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/rules/risk-rules', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok([]) }),
  )
  await page.route('**/api/v1/rules/config', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        match_scan_window_days: 30,
        match_exact_window_days: 7,
        match_suggest_window_days: 14,
        industry_template: 'it_software',
      }),
    }),
  )
  await page.route('**/api/v1/rules/exception-assignment', async (route) => {
    if (route.request().method() === 'PUT') {
      assignmentBody = postBody(route)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: ok({
          items: assignmentItems.map((item) =>
            item.exception_type === 'unknown_receipt'
              ? { ...item, owner_user_id: 7, owner_name: '财务专员' }
              : item,
          ),
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ items: assignmentItems }),
    })
  })
  await page.route('**/api/v1/system/users?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        items: [
          { id: 7, login_name: 'finance02', display_name: '财务专员', status: 'active' },
          { id: 8, login_name: 'finance03', display_name: '出纳', status: 'active' },
        ],
        page: 1,
        page_size: 100,
        total: 2,
      }),
    }),
  )
  await page.route('**/api/v1/rules/industry-alerts/run', async (route) => {
    alertsRequested = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        acceptance_overdue: 2,
        warranty_overdue: 1,
        customer_risk: 0,
        payroll_gap: 0,
        project_cashflow_risk: 3,
      }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '规则中心' }).click()
  await page.getByRole('button', { name: '异常自动分派' }).click()
  const assignmentDialog = page.getByRole('dialog', { name: '异常自动分派' })
  await expect(assignmentDialog).toBeVisible()
  await expect(assignmentDialog.locator('tr', { hasText: '资金缺口' })).toBeVisible()

  const unknownRow = assignmentDialog.locator('tr', { hasText: '待认领' })
  await unknownRow.locator('select').selectOption('7')
  await assignmentDialog.getByRole('button', { name: '保存分派配置' }).click()
  await expect(page.getByText('异常自动分派配置已保存。')).toBeVisible()
  expect(assignmentBody).toMatchObject({
    assignments: { unknown_receipt: 7, unreceived: null, fund_gap: null },
  })
  await assignmentDialog.getByRole('button', { name: '关闭', exact: true }).click()

  await page.getByRole('button', { name: '立即运行' }).click()
  await expect(page.getByText('行业预警已运行，幂等不重复生成异常。')).toBeVisible()
  await expect(page.getByText('验收款逾期 2')).toBeVisible()
  await expect(page.getByText('项目现金流风险 3')).toBeVisible()
  expect(alertsRequested).toBe(true)
})

test('流水详情生成分类建议并应用', async ({ page }) => {
  let classifyBody: Record<string, unknown> | null = null
  const transaction = {
    id: 500,
    bank_account_id: 1,
    transaction_no: 'TXN-500',
    transaction_date: '2026-10-01',
    direction: 'expense',
    amount: '88.00',
    balance_after: null,
    counterparty_name: '服务商',
    summary: '技术服务费',
    purpose: null,
    category: null,
    match_status: 'unmatched',
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
          },
        ],
        page: 1,
        page_size: 20,
        total: 1,
      }),
    }),
  )
  await page.route('**/api/v1/bank-transactions?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ items: [transaction], page: 1, page_size: 20, total: 1 }),
    }),
  )
  await page.route('**/api/v1/bank-transactions/500', async (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ ...transaction, receipts: [] }),
    })
  })
  await page.route('**/api/v1/bank-transactions/500/suggest-category', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        suggested_category: '技术服务费',
        source: 'rule',
        reason: '摘要包含「技术服务费」关键词',
        current_category: null,
      }),
    }),
  )
  await page.route('**/api/v1/bank-transactions/500/manual-classify', async (route) => {
    classifyBody = postBody(route)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ ...transaction, category: '技术服务费' }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '流水查询' }).click()
  await page.getByRole('button', { name: '查看详情' }).click()
  const detailDialog = page.getByRole('dialog', { name: '流水详情' })
  await detailDialog.getByRole('button', { name: '生成分类建议' }).click()
  await expect(detailDialog.getByText('技术服务费').first()).toBeVisible()
  await expect(detailDialog.locator('.pill', { hasText: '规则' })).toBeVisible()
  await expect(detailDialog.getByText(/摘要包含「技术服务费」关键词/)).toBeVisible()

  await detailDialog.getByRole('button', { name: '应用建议分类' }).click()
  await expect(detailDialog.getByText('分类已应用为「技术服务费」。')).toBeVisible()
  expect(classifyBody).toMatchObject({ category: '技术服务费' })
})

test('工作台详情抽屉四区动线与下一笔导航', async ({ page }) => {
  await mockLoginAndDashboard(page)
  const carrier = {
    ...exceptionCase('new'),
    exception_no: 'EXC-0001',
    exception_type: 'partial_receipt',
    title: '部分收款：软件实施合同',
    biz_amount: '500.00',
    match_group_id: 'MG-1',
    match_summary: {
      match_group_id: 'MG-1',
      match_status: 'suggested',
      confidence: 'medium',
      txn_no: 'TX-1',
      amount: '500.00',
      contract_no: 'CT-1',
      result_id: 88,
      counterparty_name: '甲方科技',
      summary: '首付款',
      node_name: '首付款',
    },
  }
  const other = {
    ...exceptionCase('new'),
    id: 602,
    exception_no: 'EXC-0002',
    title: '未知收款：乙方',
  }
  const state = { confirmed: false }
  await page.route('**/api/v1/matching/exceptions?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        items: state.confirmed ? [other] : [carrier, other],
        page: 1,
        page_size: 20,
        total: state.confirmed ? 1 : 2,
      }),
    }),
  )
  for (const [id, entity] of [
    [601, carrier],
    [602, other],
  ] as const) {
    await page.route(`**/api/v1/matching/exceptions/${id}`, async (route) => {
      if (route.request().method() !== 'GET') return route.fallback()
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: ok({ exception: entity, logs: [], attachments: [], source: [] }),
      })
    })
  }
  await page.route('**/api/v1/matching/results/88', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        result: { id: 88, match_group_id: 'MG-1', match_status: 'suggested' },
        transaction: {},
        allocations: [
          { id: 1, contract_no: 'CT-1', node_name: '首付款', allocated_amount: '500.00' },
        ],
        audit_logs: [],
        exceptions: [],
      }),
    }),
  )
  await page.route('**/api/v1/counterparty-aliases**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok([]) }),
  )
  await page.route('**/api/v1/matching/results/88/confirm', async (route) => {
    state.confirmed = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ id: 88, match_status: 'confirmed' }),
    })
  })

  await login(page)
  await page.getByRole('link', { name: '待办处理' }).click()
  const row = page.locator('.dense-table tbody tr', { hasText: 'EXC-0001' })
  await row.getByRole('button', { name: '详情' }).click()

  const dialog = page.getByRole('dialog', { name: /这笔款怎么回事（EXC-0001）/ })
  await expect(dialog.getByRole('heading', { name: '① 钱是谁打的' })).toBeVisible()
  await expect(dialog.getByText(/流水号 TX-1/)).toBeVisible()
  await expect(dialog.getByRole('heading', { name: '② 应该对哪笔' })).toBeVisible()
  await expect(dialog.getByText(/建议合同 CT-1/)).toBeVisible()
  await expect(dialog.getByRole('heading', { name: '③ 操作' })).toBeVisible()
  await expect(dialog.getByRole('button', { name: '确认核销' })).toBeVisible()
  await expect(dialog.getByRole('heading', { name: '④ 处理留痕' })).toBeVisible()

  // 下一笔导航：非承载工单无 ② 区
  await dialog.getByRole('button', { name: '下一笔' }).click()
  const dialogB = page.getByRole('dialog', { name: /这笔款怎么回事（EXC-0002）/ })
  await expect(dialogB).toBeVisible()
  await expect(dialogB.getByRole('heading', { name: '② 应该对哪笔' })).toBeHidden()

  // 确认核销后行从队列消失，抽屉自动跳下一笔
  await dialogB.getByRole('button', { name: '上一笔' }).click()
  await dialog.getByRole('button', { name: '确认核销' }).click()
  await expect(page.getByRole('dialog', { name: /这笔款怎么回事（EXC-0002）/ })).toBeVisible()
  await expect(page.locator('.dense-table tbody tr')).toHaveCount(1)
})

test('工作台角色默认视图与清零反馈', async ({ page }) => {
  let lastListUrl = ''
  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/matching/exceptions/stats', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        active_count: 0,
        unassigned_count: 0,
        my_todo_count: 0,
        pending_close_count: 0,
        overdue_count: 0,
        closed_count: 0,
        false_positive_count: 0,
        total_count: 0,
        closure_rate: 0,
        avg_resolution_hours: 0,
        today_new_count: 0,
        review_pending_count: 0,
        large_amount_count: 0,
        large_amount_threshold: 100000,
        by_type: [],
        by_owner: [],
      }),
    }),
  )
  await page.route('**/api/v1/matching/exceptions?**', async (route) => {
    lastListUrl = route.request().url()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ items: [], page: 1, page_size: 20, total: 0 }),
    })
  })

  // CFO 默认落「待分派」
  await login(page)
  await page.getByRole('link', { name: '待办处理' }).click()
  await expect.poll(() => lastListUrl).toContain('queue=unassigned')
  await expect(page.getByRole('tab', { name: '待分派' })).toHaveAttribute('aria-selected', 'true')

  // 切到「我的待办」后 localStorage 记忆；空队列显示清零反馈
  await page.getByRole('tab', { name: '我的待办' }).click()
  await expect.poll(() => lastListUrl).toContain('queue=mine')
  await expect(page.getByText('今日待办已全部处理 ✓')).toBeVisible()
  await page.getByRole('link', { name: '管理驾驶舱' }).click()
  await page.getByRole('link', { name: '待办处理' }).click()
  await expect(page.getByRole('tab', { name: '我的待办' })).toHaveAttribute('aria-selected', 'true')
})

test('深链重定向自动打开对应抽屉', async ({ page }) => {
  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/matching/exceptions?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ items: [exceptionCase('new')], page: 1, page_size: 20, total: 1 }),
    }),
  )
  await page.route('**/api/v1/matching/exceptions/601', async (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ exception: exceptionCase('new'), logs: [], attachments: [], source: [] }),
    })
  })
  await page.route('**/api/v1/matching/results?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ items: [], page: 1, page_size: 20, total: 0 }),
    }),
  )
  await page.route('**/api/v1/matching/results/88', async (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        result: { id: 88, match_group_id: 'MG-88', match_status: 'confirmed' },
        transaction: {},
        allocations: [],
        audit_logs: [],
        exceptions: [],
      }),
    })
  })
  await page.route('**/api/v1/counterparty-aliases**', async (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: ok([]) }),
  )

  await login(page)
  // /exceptions/:id → 工作台并自动打开对应抽屉
  await page.goto('/#/exceptions/601')
  await expect(page).toHaveURL(/#\/exceptions\?exception=601/)
  await expect(page.getByRole('dialog', { name: /这笔款怎么回事（EXC-0001）/ })).toBeVisible()
  await page
    .getByRole('dialog', { name: /这笔款怎么回事/ })
    .locator('footer')
    .getByRole('button', { name: '关闭', exact: true })
    .click()

  // /matching/:id → 全部记录视图并自动打开归档详情
  await page.goto('/#/matching/88')
  await expect(page).toHaveURL(/#\/exceptions\?match_result=88/)
  await expect(page.getByRole('tab', { name: '全部记录' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('dialog', { name: '匹配结果详情' })).toBeVisible()
})

test('全部记录视图展示匹配归档与对手方别名维护', async ({ page }) => {
  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/matching/exceptions?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ items: [exceptionCase('closed')], page: 1, page_size: 20, total: 1 }),
    }),
  )
  await page.route('**/api/v1/matching/results?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        items: [
          {
            id: 88,
            match_group_id: 'MG-1',
            allocation_mode: 'single',
            allocated_amount: '500.00',
            allocation_count: 1,
            allocation_total: '500.00',
            bank_transaction_id: 1,
            transaction_no: 'TX-1',
            amount: '500.00',
            transaction_match_status: 'matched',
            contract_id: 1,
            contract_receivable_plan_id: 1,
            contract_no: 'CT-1',
            contract_name: '软件实施合同',
            node_name: '首付款',
            match_type: 'exact',
            confidence_level: 'high',
            match_status: 'confirmed',
            match_reason: '摘要包含合同编号且金额、日期一致',
          },
        ],
        page: 1,
        page_size: 20,
        total: 1,
      }),
    }),
  )
  const aliases: Array<{ id: number; alias: string; canonical_name: string; updated_at: string }> =
    []
  await page.route('**/api/v1/counterparty-aliases**', async (route) => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON() as { alias: string; canonical_name: string }
      aliases.push({ id: 1, ...body, updated_at: '2026-10-21 10:00:00' })
      await route.fulfill({ status: 200, contentType: 'application/json', body: ok(aliases[0]) })
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: ok(aliases) })
  })

  await login(page)
  await page.getByRole('link', { name: '待办处理' }).click()
  await page.getByRole('tab', { name: '全部记录' }).click()

  // 匹配结果只读归档：无确认/拒绝按钮，含已核销记录
  await expect(page.getByRole('heading', { name: '匹配结果归档' })).toBeVisible()
  await expect(page.getByText('TX-1')).toBeVisible()
  await expect(page.getByText('已核销', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '确认组' })).toBeHidden()
  await expect(page.getByRole('button', { name: '拒绝组' })).toBeHidden()

  // 对手方别名维护
  await page.getByRole('button', { name: '对手方别名' }).click()
  const aliasDialog = page.getByRole('dialog', { name: '对手方别名' })
  await expect(aliasDialog.getByText('暂无对手方别名。')).toBeVisible()
  await aliasDialog.getByLabel('别名').fill('甲方科技分公司')
  await aliasDialog.getByLabel('标准名称').fill('甲方科技')
  await aliasDialog.getByRole('button', { name: '新增别名' }).click()
  await expect(aliasDialog.getByText('别名已新增。')).toBeVisible()
  await expect(aliasDialog.getByText('甲方科技分公司')).toBeVisible()
})

test('异常详情生成解释草稿', async ({ page }) => {
  await mockLoginAndDashboard(page)
  await page.route('**/api/v1/matching/exceptions?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ items: [exceptionCase('new')], page: 1, page_size: 20, total: 1 }),
    }),
  )
  await page.route('**/api/v1/matching/exceptions/601', async (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({ exception: exceptionCase('new'), logs: [], attachments: [], source: [] }),
    })
  })
  await page.route('**/api/v1/matching/exceptions/601/explain-draft', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: ok({
        draft: '该异常为未知收款：流水未匹配到合同回款计划，请人工确认后归类。',
        source: 'llm',
      }),
    }),
  )

  await login(page)
  await page.getByRole('link', { name: '待办处理' }).click()
  const row = page.locator('.dense-table tbody tr', { hasText: 'EXC-0001' })
  await row.getByRole('button', { name: '详情' }).click()
  const detailDialog = page.getByRole('dialog', { name: /这笔款怎么回事/ })
  await detailDialog.getByRole('button', { name: '生成解释草稿' }).click()
  await expect(detailDialog.getByText('来源：大模型')).toBeVisible()
  await expect(detailDialog.locator('textarea[readonly]')).toHaveValue(
    '该异常为未知收款：流水未匹配到合同回款计划，请人工确认后归类。',
  )
})
