/** 项目风险规则字段元数据：中文名称与字段说明，供项目资金弹层和规则中心共用。 */
export const RULE_META: Record<string, { name: string; description: string }> = {
  overdue_exists: {
    name: '存在逾期应收',
    description:
      '监控项目逾期未收金额（已到期但未收足的应收计划金额合计）。当逾期金额大于阈值时触发，默认阈值 0 表示存在任意逾期即触发，默认扣 30 分。',
  },
  overdue_ratio_high: {
    name: '逾期占比过高',
    description:
      '监控逾期金额占项目应收总额的比例。阈值填小数（如 0.5 表示 50%），当占比大于阈值时触发，默认扣 15 分。',
  },
  paid_rate_low: {
    name: '回款率过低',
    description:
      '监控项目回款率（已收金额 ÷ 应收总额，应收为 0 时按 100% 计）。当回款率低于阈值（默认 0.5）时触发，默认扣 30 分，用于识别回款严重滞后的项目。',
  },
  paid_rate_mid: {
    name: '回款率偏低',
    description:
      '与「回款率过低」使用同一指标、阈值更宽松的预警线。当回款率低于阈值（默认 0.8）时触发，默认扣 15 分；两条规则可同时启用形成分级预警。',
  },
  open_exception_count: {
    name: '待处理异常',
    description:
      '监控项目关联的待处理异常数量（未关闭且未标记误报的应收异常）。当数量达到阈值（默认 1 条）时触发，按每条异常扣分（默认每条 10 分），总扣分不超过上限（默认 30 分）。',
  },
  negative_cashflow: {
    name: '净现金流为负',
    description:
      '监控项目关联银行流水的净现金流（已确认匹配的收入减支出）。当净现金流低于阈值（默认 0，即为负）时触发，默认扣 15 分。',
  },
}

export function ruleName(ruleCode: string) {
  return RULE_META[ruleCode]?.name ?? ruleCode
}
