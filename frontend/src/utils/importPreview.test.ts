import { describe, expect, it } from 'vitest'

import {
  previewColumnsFor,
  previewFieldValue,
  rateText,
  type PreviewTableRow,
} from './importPreview'

describe('previewColumnsFor', () => {
  it('返回六类导入任务的列定义', () => {
    expect(previewColumnsFor('bank_statement').map((column) => column.key)).toEqual([
      'transaction_no',
      'transaction_date',
      'direction',
      'amount',
    ])
    expect(previewColumnsFor('contract')).toHaveLength(6)
    expect(previewColumnsFor('finance_record')).toHaveLength(5)
    expect(previewColumnsFor('project')).toHaveLength(8)
    expect(previewColumnsFor('receipt')).toHaveLength(6)
    expect(previewColumnsFor('payable')).toHaveLength(6)
  })

  it('未知类型返回空列', () => {
    expect(previewColumnsFor('')).toEqual([])
    expect(previewColumnsFor('unknown')).toEqual([])
  })
})

describe('previewFieldValue', () => {
  it('优先取 payload 字段', () => {
    const row: PreviewTableRow = { payload: { contract_no: 'HT-001' } }
    expect(previewFieldValue(row, 'contract_no')).toBe('HT-001')
  })

  it('payload 缺失时回退银行流水扁平字段', () => {
    const row: PreviewTableRow = { transaction_no: 'TXN-1', amount: '100.00' }
    expect(previewFieldValue(row, 'transaction_no')).toBe('TXN-1')
    expect(previewFieldValue(row, 'amount')).toBe('100.00')
  })

  it('空值统一展示为 -', () => {
    expect(previewFieldValue({}, 'transaction_no')).toBe('-')
    expect(previewFieldValue({ payload: { contract_no: null } }, 'contract_no')).toBe('-')
    expect(previewFieldValue({ payload: { contract_no: '' } }, 'contract_no')).toBe('-')
  })

  it('方向、类型、状态字段映射为中文', () => {
    expect(previewFieldValue({ direction: 'income' }, 'direction')).toBe('收入')
    expect(previewFieldValue({ payload: { record_type: 'receipt' } }, 'record_type')).toBe('收款单')
    expect(previewFieldValue({ payload: { project_status: 'active' } }, 'project_status')).toBe(
      '进行中',
    )
    expect(previewFieldValue({ payload: { status: 'unpaid' } }, 'status')).toBe('未付')
  })
})

describe('rateText', () => {
  it('null 展示暂无数据，数值追加百分号', () => {
    expect(rateText(null)).toBe('暂无数据')
    expect(rateText(90)).toBe('90%')
  })
})
