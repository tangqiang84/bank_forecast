import { describe, expect, it } from 'vitest'

import {
  accountStatusLabel,
  allocationModeLabel,
  auditActionLabel,
  confidenceLevelLabel,
  connectionTypeLabel,
  contractStatusLabel,
  directionLabel,
  exceptionStatusLabel,
  exceptionTypeLabel,
  fieldNameLabel,
  forecastTaskStatusLabel,
  genericStatusLabel,
  healthLevelLabel,
  idleLevelLabel,
  importJobStatusLabel,
  importRowStatusLabel,
  industryTemplateLabel,
  jobTypeLabel,
  matchStatusLabel,
  matchTypeLabel,
  modelNameLabel,
  permissionModuleLabel,
  projectStatusLabel,
  receivableStatusLabel,
  recordTypeLabel,
  reportTaskStatusLabel,
  reportTypeLabel,
  riskLevelLabel,
  ruleStatusLabel,
  severityLabel,
  taskStatusLabel,
  templateRecognitionStatusLabel,
  userStatusLabel,
} from './labels'

describe('labels', () => {
  it('maps account status and falls back to the raw value', () => {
    expect(accountStatusLabel('active')).toBe('活跃')
    expect(accountStatusLabel('low_frequency')).toBe('低频')
    expect(accountStatusLabel('idle')).toBe('闲置')
    expect(accountStatusLabel('pending_close')).toBe('待销户')
    expect(accountStatusLabel('closed')).toBe('已销户')
    expect(accountStatusLabel('unknown')).toBe('unknown')
    expect(accountStatusLabel(null)).toBe('-')
    expect(accountStatusLabel(undefined)).toBe('-')
  })

  it('maps idle levels', () => {
    expect(idleLevelLabel('normal')).toBe('正常')
    expect(idleLevelLabel('idle_30')).toBe('30 天未动账')
    expect(idleLevelLabel('idle_90')).toBe('90 天未动账')
    expect(idleLevelLabel('idle_180')).toBe('180 天未动账')
    expect(idleLevelLabel('other')).toBe('other')
  })

  it('maps transaction directions', () => {
    expect(directionLabel('income')).toBe('收入')
    expect(directionLabel('expense')).toBe('支出')
    expect(directionLabel('transfer')).toBe('内部转账')
    expect(directionLabel('refund')).toBe('退款')
    expect(directionLabel('reversal')).toBe('冲正')
    expect(directionLabel('other')).toBe('other')
  })

  it('maps match status including backend confirmed/rejected', () => {
    expect(matchStatusLabel('unmatched')).toBe('未匹配')
    expect(matchStatusLabel('suggested')).toBe('待确认')
    expect(matchStatusLabel('matched')).toBe('已自动匹配')
    expect(matchStatusLabel('manual_confirmed')).toBe('已人工确认')
    expect(matchStatusLabel('confirmed')).toBe('已确认')
    expect(matchStatusLabel('rejected')).toBe('已拒绝')
    expect(matchStatusLabel('other')).toBe('other')
  })

  it('maps match types including backend partial/finance_reconcile', () => {
    expect(matchTypeLabel('exact')).toBe('精确匹配')
    expect(matchTypeLabel('amount_date')).toBe('金额日期匹配')
    expect(matchTypeLabel('similar')).toBe('相似匹配')
    expect(matchTypeLabel('split')).toBe('拆分匹配')
    expect(matchTypeLabel('merge')).toBe('合并匹配')
    expect(matchTypeLabel('partial')).toBe('部分金额匹配')
    expect(matchTypeLabel('finance_reconcile')).toBe('财务对账匹配')
    expect(matchTypeLabel('other')).toBe('other')
  })

  it('maps confidence levels', () => {
    expect(confidenceLevelLabel('high')).toBe('高')
    expect(confidenceLevelLabel('medium')).toBe('中')
    expect(confidenceLevelLabel('low')).toBe('低')
    expect(confidenceLevelLabel('other')).toBe('other')
  })

  it('maps allocation modes', () => {
    expect(allocationModeLabel('single')).toBe('单笔')
    expect(allocationModeLabel('multi')).toBe('多笔合计')
    expect(allocationModeLabel('other')).toBe('other')
  })

  it('maps receivable status for both doc and backend values', () => {
    expect(receivableStatusLabel('pending')).toBe('未到期')
    expect(receivableStatusLabel('partial_received')).toBe('部分收款')
    expect(receivableStatusLabel('received')).toBe('已收齐')
    expect(receivableStatusLabel('overdue')).toBe('逾期未收')
    expect(receivableStatusLabel('closed')).toBe('已关闭')
    expect(receivableStatusLabel('unpaid')).toBe('未收款')
    expect(receivableStatusLabel('partial')).toBe('部分收款')
    expect(receivableStatusLabel('paid')).toBe('已收齐')
    expect(receivableStatusLabel('other')).toBe('other')
  })

  it('maps project status', () => {
    expect(projectStatusLabel('active')).toBe('进行中')
    expect(projectStatusLabel('paused')).toBe('已暂停')
    expect(projectStatusLabel('completed')).toBe('已完成')
    expect(projectStatusLabel('cancelled')).toBe('已取消')
    expect(projectStatusLabel('other')).toBe('other')
  })

  it('maps risk level and severity with the same scale', () => {
    expect(riskLevelLabel('high')).toBe('高')
    expect(riskLevelLabel('medium')).toBe('中')
    expect(riskLevelLabel('low')).toBe('低')
    expect(severityLabel('high')).toBe('高')
    expect(severityLabel('medium')).toBe('中')
    expect(severityLabel('low')).toBe('低')
    expect(severityLabel('other')).toBe('other')
  })

  it('maps exception status', () => {
    expect(exceptionStatusLabel('new')).toBe('新发现')
    expect(exceptionStatusLabel('in_progress')).toBe('处理中')
    expect(exceptionStatusLabel('resolved')).toBe('已解决')
    expect(exceptionStatusLabel('closed')).toBe('已关闭')
    expect(exceptionStatusLabel('false_positive')).toBe('误报关闭')
    expect(exceptionStatusLabel('other')).toBe('other')
  })

  it('maps exception types and falls back for unknown values', () => {
    expect(exceptionTypeLabel('receivable_unreceived')).toBe('应收未收')
    expect(exceptionTypeLabel('payable_unpaid')).toBe('应付未付')
    expect(exceptionTypeLabel('transaction_missing')).toBe('流水缺失')
    expect(exceptionTypeLabel('transaction_duplicate')).toBe('流水重复')
    expect(exceptionTypeLabel('rule_hit')).toBe('规则命中异常')
    expect(exceptionTypeLabel('manual_exception')).toBe('人工创建异常')
    expect(exceptionTypeLabel('unreceived')).toBe('应收未收')
    expect(exceptionTypeLabel('unknown_receipt')).toBe('未知收款')
    expect(exceptionTypeLabel('timing_difference')).toBe('跨月时间差')
    expect(exceptionTypeLabel('unknown_income')).toBe('unknown_income')
  })

  it('maps import job status', () => {
    expect(importJobStatusLabel('pending')).toBe('待处理')
    expect(importJobStatusLabel('running')).toBe('处理中')
    expect(importJobStatusLabel('preview_pending')).toBe('待确认预览')
    expect(importJobStatusLabel('preview_failed')).toBe('预览校验失败')
    expect(importJobStatusLabel('success')).toBe('成功')
    expect(importJobStatusLabel('partial_success')).toBe('部分成功')
    expect(importJobStatusLabel('failed')).toBe('失败')
    expect(importJobStatusLabel('other')).toBe('other')
  })

  it('maps import row status including confirmed preview rows', () => {
    expect(importRowStatusLabel('valid')).toBe('有效')
    expect(importRowStatusLabel('failed')).toBe('失败')
    expect(importRowStatusLabel('skipped')).toBe('跳过')
    expect(importRowStatusLabel('retry_success')).toBe('重试成功')
    expect(importRowStatusLabel('confirmed')).toBe('已确认')
    expect(importRowStatusLabel('other')).toBe('other')
  })

  it('maps job types', () => {
    expect(jobTypeLabel('bank_statement')).toBe('银行流水导入')
    expect(jobTypeLabel('contract')).toBe('合同导入')
    expect(jobTypeLabel('project')).toBe('项目导入')
    expect(jobTypeLabel('finance_record')).toBe('财务记录导入')
    expect(jobTypeLabel('receipt')).toBe('回单处理')
    expect(jobTypeLabel('match')).toBe('匹配任务')
    expect(jobTypeLabel('report')).toBe('报表任务')
    expect(jobTypeLabel('other')).toBe('other')
  })

  it('maps finance record types', () => {
    expect(recordTypeLabel('receipt')).toBe('收款单')
    expect(recordTypeLabel('payment')).toBe('付款单')
    expect(recordTypeLabel('voucher')).toBe('凭证')
    expect(recordTypeLabel('journal')).toBe('日记账')
    expect(recordTypeLabel('other')).toBe('other')
  })

  it('maps report types', () => {
    expect(reportTypeLabel('daily')).toBe('日报')
    expect(reportTypeLabel('weekly')).toBe('周报')
    expect(reportTypeLabel('monthly')).toBe('月报')
    expect(reportTypeLabel('health')).toBe('资金体检')
    expect(reportTypeLabel('custom')).toBe('自定义报表')
    expect(reportTypeLabel('other')).toBe('other')
  })

  it('maps task status and shares it across report and forecast tasks', () => {
    expect(taskStatusLabel('running')).toBe('处理中')
    expect(taskStatusLabel('success')).toBe('成功')
    expect(taskStatusLabel('failed')).toBe('失败')
    expect(reportTaskStatusLabel).toBe(taskStatusLabel)
    expect(forecastTaskStatusLabel).toBe(taskStatusLabel)
    expect(taskStatusLabel('other')).toBe('other')
  })

  it('maps connection types', () => {
    expect(connectionTypeLabel('file')).toBe('文件导入')
    expect(connectionTypeLabel('rpa')).toBe('RPA 接入')
    expect(connectionTypeLabel('direct_connect')).toBe('直连')
    expect(connectionTypeLabel('other')).toBe('other')
  })

  it('maps user status', () => {
    expect(userStatusLabel('active')).toBe('启用')
    expect(userStatusLabel('disabled')).toBe('停用')
    expect(userStatusLabel('other')).toBe('other')
  })

  it('maps health levels', () => {
    expect(healthLevelLabel('healthy')).toBe('健康')
    expect(healthLevelLabel('warning')).toBe('关注')
    expect(healthLevelLabel('danger')).toBe('风险')
    expect(healthLevelLabel('other')).toBe('other')
  })

  it('maps model names and falls back for unknown models', () => {
    expect(modelNameLabel('moving-average-with-trend')).toBe('移动平均（含趋势）')
    expect(modelNameLabel('moving-average')).toBe('moving-average')
  })

  it('maps audit actions and falls back to the raw value', () => {
    expect(auditActionLabel('CREATE_REPORT')).toBe('生成报表')
    expect(auditActionLabel('DOWNLOAD_REPORT')).toBe('下载报表')
    expect(auditActionLabel('PRINT_REPORT')).toBe('打印预览')
    expect(auditActionLabel('TEST_BANK_CONNECTION')).toBe('测试接入连接')
    expect(auditActionLabel('SOME_FUTURE_ACTION')).toBe('SOME_FUTURE_ACTION')
  })

  it('maps the full backend audit action set', () => {
    expect(auditActionLabel('CREATE_BANK_ACCOUNT')).toBe('新建银行账户')
    expect(auditActionLabel('UPDATE_BANK_ACCOUNT')).toBe('更新银行账户')
    expect(auditActionLabel('CLOSE_BANK_ACCOUNT')).toBe('账户销户')
    expect(auditActionLabel('SCAN_IDLE_BANK_ACCOUNTS')).toBe('盘点闲置账户')
    expect(auditActionLabel('CLASSIFY_BANK_TRANSACTION')).toBe('分类流水')
    expect(auditActionLabel('BATCH_CLASSIFY_BANK_TRANSACTION')).toBe('批量分类流水')
    expect(auditActionLabel('BATCH_UNLINK_BANK_TRANSACTION')).toBe('批量解除流水关联')
    expect(auditActionLabel('UNLINK_BANK_TRANSACTION')).toBe('解除流水关联')
    expect(auditActionLabel('CONFIRM_MATCH_RESULT')).toBe('确认匹配结果')
    expect(auditActionLabel('REJECT_MATCH_RESULT')).toBe('拒绝匹配结果')
    expect(auditActionLabel('RUN_RECEIVABLE_MATCH')).toBe('运行回款匹配')
    expect(auditActionLabel('RUN_FINANCE_RECONCILIATION')).toBe('运行财务对账')
    expect(auditActionLabel('RUN_FORECAST')).toBe('运行现金预测')
    expect(auditActionLabel('BACKFILL_FORECAST_ACTUALS')).toBe('回填实际金额')
    expect(auditActionLabel('ACTIVATE_FORECAST_MODEL')).toBe('启用预测模型')
    expect(auditActionLabel('IMPORT_BANK_STATEMENT')).toBe('导入银行流水')
    expect(auditActionLabel('PREVIEW_BANK_STATEMENT')).toBe('预览银行流水')
    expect(auditActionLabel('CONFIRM_BANK_STATEMENT')).toBe('确认银行流水入账')
    expect(auditActionLabel('RETRY_BANK_STATEMENT_ERRORS')).toBe('重试流水失败行')
    expect(auditActionLabel('IMPORT_CONTRACT')).toBe('导入合同')
    expect(auditActionLabel('IMPORT_PROJECT')).toBe('导入项目')
    expect(auditActionLabel('IMPORT_FINANCE_RECORD')).toBe('导入财务记录')
    expect(auditActionLabel('IMPORT_RECEIPT')).toBe('导入回单')
    expect(auditActionLabel('ASSIGN_EXCEPTION')).toBe('分派异常')
    expect(auditActionLabel('COMMENT_EXCEPTION')).toBe('异常备注')
    expect(auditActionLabel('RESOLVE_EXCEPTION')).toBe('处理完成异常')
    expect(auditActionLabel('CLOSE_EXCEPTION')).toBe('关闭异常')
    expect(auditActionLabel('FALSE_POSITIVE_EXCEPTION')).toBe('标记误报')
    expect(auditActionLabel('CREATE_USER')).toBe('新建用户')
    expect(auditActionLabel('RESET_USER_PASSWORD')).toBe('重置用户密码')
    expect(auditActionLabel('UPDATE_ROLE_PERMISSIONS')).toBe('更新角色权限')
    expect(auditActionLabel('UPDATE_PROJECT_SCOPE')).toBe('更新项目数据范围')
    expect(auditActionLabel('BATCH_UPDATE_PROJECT_STATUS')).toBe('批量更新项目状态')
    expect(auditActionLabel('APPLY_INDUSTRY_TEMPLATE')).toBe('应用行业模板')
    expect(auditActionLabel('ROLLBACK_PROJECT_RISK_RULE')).toBe('回滚风险规则')
    expect(auditActionLabel('UPDATE_RULE_CONFIG')).toBe('更新规则配置')
  })

  it('maps exception action_type codes used in the timeline', () => {
    expect(auditActionLabel('ASSIGN')).toBe('分派')
    expect(auditActionLabel('COMMENT')).toBe('备注')
    expect(auditActionLabel('RESOLVE')).toBe('处理完成')
    expect(auditActionLabel('CLOSE')).toBe('关闭')
    expect(auditActionLabel('FALSE_POSITIVE')).toBe('标记误报')
  })

  it('maps industry templates', () => {
    expect(industryTemplateLabel('it_software')).toBe('IT 软件与信息服务')
    expect(industryTemplateLabel('other')).toBe('other')
  })

  it('maps permission modules', () => {
    expect(permissionModuleLabel('dashboard')).toBe('驾驶舱')
    expect(permissionModuleLabel('account')).toBe('账户')
    expect(permissionModuleLabel('system')).toBe('系统管理')
    expect(permissionModuleLabel('other')).toBe('other')
  })

  it('maps rule and contract status', () => {
    expect(ruleStatusLabel('enabled')).toBe('启用')
    expect(ruleStatusLabel('disabled')).toBe('禁用')
    expect(contractStatusLabel('active')).toBe('生效中')
    expect(contractStatusLabel('closed')).toBe('已关闭')
    expect(contractStatusLabel('disabled')).toBe('已停用')
  })

  it('maps generic status by union order and falls back', () => {
    expect(genericStatusLabel('new')).toBe('新发现')
    expect(genericStatusLabel('preview_pending')).toBe('待确认预览')
    expect(genericStatusLabel('unpaid')).toBe('未收款')
    expect(genericStatusLabel('active')).toBe('活跃')
    expect(genericStatusLabel('disabled')).toBe('停用')
    expect(genericStatusLabel('paused')).toBe('已暂停')
    expect(genericStatusLabel('other')).toBe('other')
    expect(genericStatusLabel(null)).toBe('-')
  })

  it('maps template recognition status', () => {
    expect(templateRecognitionStatusLabel('recognized')).toBe('已识别')
    expect(templateRecognitionStatusLabel('unsupported')).toBe('未识别')
    expect(templateRecognitionStatusLabel('other')).toBe('other')
  })

  it('maps field names and falls back to the raw key', () => {
    expect(fieldNameLabel('transaction_no')).toBe('流水号')
    expect(fieldNameLabel('match_status')).toBe('匹配状态')
    expect(fieldNameLabel('unlisted_key')).toBe('unlisted_key')
  })

  it('maps detail field names', () => {
    expect(fieldNameLabel('bank_code')).toBe('银行代码')
    expect(fieldNameLabel('bank_name')).toBe('开户银行')
    expect(fieldNameLabel('account_no_last4')).toBe('账号后四位')
    expect(fieldNameLabel('current_balance')).toBe('当前余额')
    expect(fieldNameLabel('idle_level')).toBe('闲置等级')
    expect(fieldNameLabel('purpose')).toBe('用途')
    expect(fieldNameLabel('contract_name')).toBe('合同名称')
    expect(fieldNameLabel('project_status')).toBe('项目状态')
    expect(fieldNameLabel('risk_score')).toBe('风险评分')
    expect(fieldNameLabel('title')).toBe('标题')
    expect(fieldNameLabel('description')).toBe('描述')
    expect(fieldNameLabel('match_reason')).toBe('匹配理由')
    expect(fieldNameLabel('match_group_id')).toBe('匹配组')
    expect(fieldNameLabel('allocated_amount')).toBe('分配金额')
    expect(fieldNameLabel('plan_amount')).toBe('计划金额')
    expect(fieldNameLabel('node_name')).toBe('节点名称')
    expect(fieldNameLabel('finance_subject')).toBe('财务科目')
    expect(fieldNameLabel('created_at')).toBe('创建时间')
    expect(fieldNameLabel('updated_at')).toBe('更新时间')
  })

  it('maps report result metric names aligned with backend METRIC_LABELS', () => {
    expect(fieldNameLabel('net_cashflow')).toBe('净流入')
    expect(fieldNameLabel('transaction_count')).toBe('交易笔数')
    expect(fieldNameLabel('receivable_plan_total')).toBe('应收总额')
    expect(fieldNameLabel('receivable_paid_total')).toBe('已收总额')
    expect(fieldNameLabel('overdue_receivable_total')).toBe('逾期未收')
    expect(fieldNameLabel('pending_exception_count')).toBe('待处理异常')
    expect(fieldNameLabel('match_rate')).toBe('匹配率')
    expect(fieldNameLabel('health_score')).toBe('健康评分')
    expect(fieldNameLabel('health_level')).toBe('健康等级')
    expect(fieldNameLabel('receivable_due_in_week')).toBe('本周到期应收')
    expect(fieldNameLabel('confirmed_receipts_in_week')).toBe('本周确认回款')
    expect(fieldNameLabel('active_account_total')).toBe('账户总数')
    expect(fieldNameLabel('active_accounts_in_week')).toBe('本周动账账户')
    expect(fieldNameLabel('exceptions_closed_in_week')).toBe('本周闭环异常')
  })
})
