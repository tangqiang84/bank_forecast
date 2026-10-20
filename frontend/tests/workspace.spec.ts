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
            permissions: ALL_PERMISSIONS,
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
  await page.route('**/api/v1/bank-connections**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: [], trace_id: 'e2e' }),
    }),
  )
  await page.route('**/api/v1/contracts', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          message: 'ok',
          data: {
            id: 201,
            contract_no: 'HT-E2E-001',
            contract_name: '端到端合同',
            status: 'active',
          },
          trace_id: 'e2e',
        }),
      })
      return
    }
    await route.fallback()
  })
  await page.route('**/api/v1/contracts?**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: {
          items: [
            {
              id: 200,
              contract_no: 'HT-E2E-000',
              contract_name: '示例合同',
              customer_name: '示例客户',
              project_name: null,
              contract_amount: '10000.00',
              receivable_amount: '0.00',
              paid_amount: '0.00',
            },
          ],
          total: 1,
        },
        trace_id: 'e2e',
      }),
    }),
  )
  await page.route('**/api/v1/receivable-plans', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          message: 'ok',
          data: { id: 301, contract_id: 200, node_name: '首付款', status: 'unpaid' },
          trace_id: 'e2e',
        }),
      })
      return
    }
    await route.fallback()
  })
  await page.route('**/api/v1/payables', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          message: 'ok',
          data: { id: 401, supplier_name: '端到端供应商', node_name: '到货款', status: 'unpaid' },
          trace_id: 'e2e',
        }),
      })
      return
    }
    await route.fallback()
  })
  await page.route('**/api/v1/payables?**', async (route) =>
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
  await page.route('**/api/v1/projects', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          message: 'ok',
          data: {
            id: 101,
            project_no: 'PRJ-E2E-001',
            project_name: '端到端手工项目',
            project_status: 'active',
          },
          trace_id: 'e2e',
        }),
      })
      return
    }
    await route.fallback()
  })
  await page.route('**/api/v1/projects/risk-rules', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: [
          {
            id: 1,
            rule_code: 'overdue_exists',
            threshold: '0',
            penalty: '30',
            max_penalty: null,
            enabled: true,
            updated_at: '2026-10-20T00:00:00',
          },
          {
            id: 2,
            rule_code: 'paid_rate_low',
            threshold: '0.5',
            penalty: '30',
            max_penalty: null,
            enabled: true,
            updated_at: '2026-10-20T00:00:00',
          },
        ],
        trace_id: 'e2e',
      }),
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
  await page.getByRole('button', { name: '对账', exact: true }).click()
  const reconDialog = page.getByRole('dialog', { name: '银行账 / 财务账对账' })
  await expect(reconDialog.getByRole('heading', { name: '对账准备' })).toBeVisible()
  await expect(reconDialog.getByRole('heading', { name: '差异结果' })).toBeVisible()
  await page.getByRole('button', { name: '关闭弹层' }).click()

  await page.getByRole('link', { name: '现金预测' }).click()
  await expect(page.getByRole('heading', { name: '现金流预测工作台' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '预测任务详情' })).toBeVisible()
})

test('恢复核心业务模块的原有操作入口', async ({ page }) => {
  await mockWorkspaceApi(page)
  await login(page)

  await page.getByRole('link', { name: '银行账户' }).click()
  await expect(page.getByRole('heading', { name: '账户盘点' })).toBeVisible()
  await expect(page.getByRole('button', { name: '导入银行流水' })).toBeVisible()
  await expect(page.getByRole('button', { name: '新增银行账户' })).toBeVisible()
  await expect(page.getByRole('button', { name: '银行接入配置' })).toBeVisible()
  await expect(page.getByRole('button', { name: '银行模板字典' })).toBeVisible()
  await page.getByRole('button', { name: '导入银行流水' }).click()
  await expect(
    page.getByRole('dialog', { name: '导入银行流水' }).getByRole('button', { name: '预览导入' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '关闭弹层' }).click()

  await page.getByRole('link', { name: '合同应收' }).click()
  await expect(page.getByRole('heading', { name: '合同列表' })).toBeVisible()
  await expect(page.getByRole('button', { name: '导入合同 / 应收计划' })).toBeVisible()
  await page.getByRole('button', { name: '新增合同' }).click()
  const contractDialog = page.getByRole('dialog', { name: '新增合同' })
  await expect(contractDialog).toBeVisible()
  await contractDialog.getByLabel('合同编号').fill('HT-E2E-001')
  await contractDialog.getByLabel('合同名称').fill('端到端合同')
  await contractDialog.getByLabel('客户名称').fill('端到端客户')
  await contractDialog.getByLabel('合同金额').fill('10000')
  await contractDialog.getByRole('button', { name: '保存合同' }).click()
  await expect(contractDialog.getByText('合同「端到端合同」已创建。')).toBeVisible()
  await contractDialog.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByRole('button', { name: '应收计划与应付计划' }).click()
  const plansDialog = page.getByRole('dialog', { name: '应收计划与应付计划' })
  await expect(plansDialog).toBeVisible()
  await plansDialog.getByRole('button', { name: '新增应收计划' }).click()
  const planDialog = page.getByRole('dialog', { name: '新增应收计划' })
  await expect(planDialog).toBeVisible()
  await planDialog.getByLabel('关联合同').selectOption({ label: 'HT-E2E-000 · 示例合同' })
  await planDialog.getByLabel('节点名称').fill('首付款')
  await planDialog.getByLabel('应收日期').fill('2026-11-01')
  await planDialog.getByLabel('计划金额').fill('4000')
  await planDialog.getByRole('button', { name: '保存应收计划' }).click()
  await expect(planDialog.getByText('应收计划「首付款」已创建。')).toBeVisible()
  await planDialog.getByRole('button', { name: '关闭', exact: true }).click()
  await plansDialog.getByRole('button', { name: '应付计划' }).click()
  await plansDialog.getByRole('button', { name: '新增应付计划' }).click()
  const payableDialog = page.getByRole('dialog', { name: '新增应付计划' })
  await expect(payableDialog).toBeVisible()
  await payableDialog.getByLabel('供应商名称').fill('端到端供应商')
  await payableDialog
    .getByLabel('关联合同（可选）')
    .selectOption({ label: 'HT-E2E-000 · 示例合同' })
  await payableDialog.getByLabel('节点名称').fill('到货款')
  await payableDialog.getByLabel('应付日期').fill('2026-11-15')
  await payableDialog.getByLabel('计划金额').fill('3000')
  await payableDialog.getByRole('button', { name: '保存应付计划' }).click()
  await expect(payableDialog.getByText('应付计划「到货款」已创建。')).toBeVisible()
  await payableDialog.getByRole('button', { name: '关闭', exact: true }).click()
  await plansDialog.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByRole('button', { name: '导入合同 / 应收计划' }).click()
  await expect(
    page
      .getByRole('dialog', { name: '导入合同 / 应收计划' })
      .getByRole('button', { name: '预览导入' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '关闭弹层' }).click()

  await page.getByRole('link', { name: '项目资金' }).click()
  await expect(page.getByRole('heading', { name: '项目资金与风险' })).toBeVisible()
  await expect(page.getByRole('button', { name: '导入项目' })).toBeVisible()
  await expect(page.getByRole('button', { name: '项目风险规则' })).toBeVisible()
  await page.getByRole('button', { name: '项目风险规则' }).click()
  const rulesDialog = page.getByRole('dialog', { name: '项目风险规则' })
  await expect(rulesDialog).toBeVisible()
  await expect(rulesDialog.getByText('存在逾期应收', { exact: true })).toBeVisible()
  await expect(rulesDialog.getByText('回款率过低', { exact: true })).toBeVisible()
  await expect(rulesDialog.getByText('overdue_exists', { exact: true })).toHaveCount(0)
  await expect(
    rulesDialog.getByRole('button', { name: '查看存在逾期应收的说明与用法' }),
  ).toBeVisible()
  await rulesDialog.getByRole('button', { name: '查看存在逾期应收的说明与用法' }).click()
  await expect(rulesDialog.getByText(/监控项目逾期未收金额/)).toBeVisible()
  await expect(rulesDialog.getByText(/字段标识：overdue_exists/)).toBeVisible()
  await rulesDialog.getByRole('button', { name: '查看存在逾期应收的说明与用法' }).click()
  await expect(rulesDialog.getByText(/监控项目逾期未收金额/)).toHaveCount(0)
  await page.getByRole('button', { name: '关闭弹层' }).click()

  await expect(page.getByRole('button', { name: '新增项目' })).toBeVisible()
  await page.getByRole('button', { name: '新增项目' }).click()
  const createDialog = page.getByRole('dialog', { name: '新增项目' })
  await expect(createDialog).toBeVisible()
  await createDialog.getByLabel('项目编号').fill('PRJ-E2E-001')
  await createDialog.getByLabel('项目名称').fill('端到端手工项目')
  await createDialog.getByRole('button', { name: '保存项目' }).click()
  await expect(createDialog.getByText('项目「端到端手工项目」已创建。')).toBeVisible()
  await createDialog.getByRole('button', { name: '关闭', exact: true }).click()

  await page.getByRole('link', { name: '匹配结果' }).click()
  await expect(page.getByRole('heading', { name: '回款匹配工作台' })).toBeVisible()
  await expect(page.getByRole('button', { name: '运行回款匹配' })).toBeVisible()

  await page.getByRole('link', { name: '异常事项' }).click()
  await expect(page.getByRole('heading', { name: '异常处理工作台' })).toBeVisible()

  await page.getByRole('link', { name: '报表中心' }).click()
  await expect(page.getByRole('heading', { name: '资金经营报告' })).toBeVisible()
  await expect(page.getByRole('button', { name: '生成报告' })).toBeVisible()
  await expect(page.getByRole('button', { name: '报表任务' })).toBeVisible()
  await page.getByRole('button', { name: '生成报告' }).click()
  await expect(
    page.getByRole('dialog', { name: '生成报告' }).getByRole('button', { name: '生成报表' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '关闭弹层' }).click()
})
