import { describe, expect, it } from 'vitest'

import type { User } from '../services/auth'
import {
  RECONCILIATION_REMINDER_DAY,
  REPORT_REMINDER_DAY,
  exceptionTodoTotal,
  isSameYearMonth,
  resolveNextStep,
  type NextStepFacts,
} from './nextStep'

function userWith(permissions: string[]): User {
  return {
    id: 1,
    tenant_id: 1,
    login_name: 'u01',
    display_name: '用户',
    roles: [],
    permissions,
  }
}

const ALL = ['exception:view', 'matching:view', 'reconciliation:view', 'report:view']

function facts(partial: Partial<NextStepFacts>): NextStepFacts {
  return {
    dayOfMonth: 15,
    todoCount: null,
    hasMonthlyReconciliation: null,
    hasMonthlyReport: null,
    ...partial,
  }
}

describe('exceptionTodoTotal', () => {
  it('sums the three todo queues with review_pending fallback', () => {
    expect(
      exceptionTodoTotal({
        my_todo_count: 2,
        unassigned_count: 3,
        pending_close_count: 4,
        review_pending_count: 5,
      }),
    ).toBe(10)
    expect(
      exceptionTodoTotal({ my_todo_count: 2, unassigned_count: 3, pending_close_count: 4 }),
    ).toBe(9)
  })
})

describe('isSameYearMonth', () => {
  it('matches by year-month prefix for both datetime formats', () => {
    expect(isSameYearMonth('2026-10-05 10:00:00', 2026, 10)).toBe(true)
    expect(isSameYearMonth('2026-10-01T00:00:00.000+00:00', 2026, 10)).toBe(true)
    expect(isSameYearMonth('2026-09-30 23:59:59', 2026, 10)).toBe(false)
    expect(isSameYearMonth('2025-10-05 10:00:00', 2026, 10)).toBe(false)
    expect(isSameYearMonth(null, 2026, 10)).toBe(false)
    expect(isSameYearMonth(undefined, 2026, 10)).toBe(false)
    expect(isSameYearMonth('', 2026, 10)).toBe(false)
  })
})

describe('resolveNextStep / transactions', () => {
  it('shows the todo guide with the real count when todos exist', () => {
    const guide = resolveNextStep(
      'transactions',
      userWith(ALL),
      facts({ todoCount: 5, hasMonthlyReconciliation: false, dayOfMonth: 26 }),
    )
    expect(guide).toEqual({
      id: 'transactions-todo',
      text: '流水已就绪，还有 5 笔回款待核销',
      link: { to: '/exceptions', label: '去处理 →' },
    })
  })

  it('requires exception or matching permission for the todo guide', () => {
    expect(
      resolveNextStep(
        'transactions',
        userWith(['reconciliation:view', 'report:view']),
        facts({ todoCount: 5, dayOfMonth: 15 }),
      ),
    ).toBeNull()
    expect(
      resolveNextStep(
        'transactions',
        userWith(['matching:view']),
        facts({ todoCount: 5, dayOfMonth: 15 }),
      )?.id,
    ).toBe('transactions-todo')
  })

  it('falls through to the month-end reconciliation guide when todos are zero', () => {
    const guide = resolveNextStep(
      'transactions',
      userWith(ALL),
      facts({
        todoCount: 0,
        dayOfMonth: RECONCILIATION_REMINDER_DAY,
        hasMonthlyReconciliation: false,
      }),
    )
    expect(guide?.id).toBe('transactions-reconciliation')
    expect(guide?.link?.to).toBe('/reconciliation')
  })

  it('honours the day-25 boundary for the reconciliation reminder', () => {
    const base = facts({ todoCount: 0, hasMonthlyReconciliation: false })
    expect(resolveNextStep('transactions', userWith(ALL), { ...base, dayOfMonth: 24 })).toBeNull()
    expect(resolveNextStep('transactions', userWith(ALL), { ...base, dayOfMonth: 25 })?.id).toBe(
      'transactions-reconciliation',
    )
  })

  it('stays silent when reconciliation already ran this month or data failed to load', () => {
    expect(
      resolveNextStep(
        'transactions',
        userWith(ALL),
        facts({ todoCount: 0, dayOfMonth: 26, hasMonthlyReconciliation: true }),
      ),
    ).toBeNull()
    expect(
      resolveNextStep(
        'transactions',
        userWith(ALL),
        facts({ todoCount: 0, dayOfMonth: 26, hasMonthlyReconciliation: null }),
      ),
    ).toBeNull()
  })
})

describe('resolveNextStep / exceptions', () => {
  it('shows nothing while todos remain', () => {
    expect(
      resolveNextStep(
        'exceptions',
        userWith(ALL),
        facts({ todoCount: 3, dayOfMonth: 28, hasMonthlyReconciliation: false }),
      ),
    ).toBeNull()
  })

  it('shows nothing while stats are unavailable', () => {
    expect(
      resolveNextStep(
        'exceptions',
        userWith(ALL),
        facts({ todoCount: null, dayOfMonth: 28, hasMonthlyReconciliation: false }),
      ),
    ).toBeNull()
  })

  it('prioritises the reconciliation guide over the report guide when todos are cleared', () => {
    const guide = resolveNextStep(
      'exceptions',
      userWith(ALL),
      facts({
        todoCount: 0,
        dayOfMonth: 28,
        hasMonthlyReconciliation: false,
        hasMonthlyReport: false,
      }),
    )
    expect(guide).toEqual({
      id: 'exceptions-reconciliation',
      text: '今日待办已清零，本月财务对账还没做',
      link: { to: '/reconciliation', label: '去对账 →' },
    })
  })

  it('honours the day-25 boundary for the reconciliation guide', () => {
    const base = facts({ todoCount: 0, hasMonthlyReconciliation: false })
    expect(resolveNextStep('exceptions', userWith(ALL), { ...base, dayOfMonth: 24 })).toBeNull()
    expect(resolveNextStep('exceptions', userWith(ALL), { ...base, dayOfMonth: 25 })?.id).toBe(
      'exceptions-reconciliation',
    )
  })

  it('shows the monthly report guide only from day 28 when reconciliation is done', () => {
    const base = facts({ todoCount: 0, hasMonthlyReconciliation: true, hasMonthlyReport: false })
    expect(resolveNextStep('exceptions', userWith(ALL), { ...base, dayOfMonth: 27 })).toBeNull()
    expect(
      resolveNextStep('exceptions', userWith(ALL), { ...base, dayOfMonth: REPORT_REMINDER_DAY }),
    ).toEqual({
      id: 'exceptions-monthly-report',
      text: '本月月报还没生成',
      link: { to: '/reports', label: '去生成 →' },
    })
  })

  it('skips guides whose link target is not permitted', () => {
    const noRecon = resolveNextStep(
      'exceptions',
      userWith(['exception:view', 'report:view']),
      facts({
        todoCount: 0,
        dayOfMonth: 28,
        hasMonthlyReconciliation: false,
        hasMonthlyReport: true,
      }),
    )
    expect(noRecon).toBeNull()
    const noReport = resolveNextStep(
      'exceptions',
      userWith(['exception:view', 'reconciliation:view']),
      facts({
        todoCount: 0,
        dayOfMonth: 28,
        hasMonthlyReconciliation: true,
        hasMonthlyReport: false,
      }),
    )
    expect(noReport).toBeNull()
  })
})

describe('resolveNextStep / reconciliation', () => {
  it('points to the in-page run button when this month has no reconciliation', () => {
    const guide = resolveNextStep(
      'reconciliation',
      userWith(ALL),
      facts({ hasMonthlyReconciliation: false }),
    )
    expect(guide).toEqual({
      id: 'reconciliation-run',
      text: '本月还没运行对账，点击右上角「运行对账」开始',
      link: null,
    })
  })

  it('stays silent while reconciliation status is unavailable', () => {
    expect(
      resolveNextStep('reconciliation', userWith(ALL), facts({ hasMonthlyReconciliation: null })),
    ).toBeNull()
  })

  it('shows the monthly report guide from day 28 after reconciliation is done', () => {
    const base = facts({ hasMonthlyReconciliation: true, hasMonthlyReport: false })
    expect(resolveNextStep('reconciliation', userWith(ALL), { ...base, dayOfMonth: 27 })).toBeNull()
    const guide = resolveNextStep('reconciliation', userWith(ALL), { ...base, dayOfMonth: 28 })
    expect(guide).toEqual({
      id: 'reconciliation-monthly-report',
      text: '对账已完成，本月月报还没生成',
      link: { to: '/reports', label: '去生成 →' },
    })
  })

  it('requires report permission for the report guide', () => {
    expect(
      resolveNextStep(
        'reconciliation',
        userWith(['reconciliation:view']),
        facts({ hasMonthlyReconciliation: true, hasMonthlyReport: false, dayOfMonth: 28 }),
      ),
    ).toBeNull()
  })
})
