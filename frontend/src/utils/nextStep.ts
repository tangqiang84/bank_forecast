import type { User } from '../services/auth'
import { navPermitted } from './navigation'

// 页面「下一步引导条」规则（菜单流程化重设计方案 §4-3，P6-6）：纯函数判断，组件只负责取数与渲染
export type NextStepContext = 'transactions' | 'exceptions' | 'reconciliation'

export type NextStepGuideModel = {
  id: string
  text: string
  link: { to: string; label: string } | null
}

// null 表示对应数据未加载成功（静默降级，该条规则不命中）
export type NextStepFacts = {
  dayOfMonth: number
  todoCount: number | null
  hasMonthlyReconciliation: boolean | null
  hasMonthlyReport: boolean | null
}

export const RECONCILIATION_REMINDER_DAY = 25
export const REPORT_REMINDER_DAY = 28

// 待办口径与侧边栏角标一致：我的待办 + 待分派 + 待复核（review_pending_count 缺失时回退 pending_close_count）
export function exceptionTodoTotal(stats: {
  my_todo_count: number
  unassigned_count: number
  pending_close_count: number
  review_pending_count?: number
}): number {
  return (
    stats.my_todo_count +
    stats.unassigned_count +
    (stats.review_pending_count ?? stats.pending_close_count)
  )
}

// 后端时间字符串形如 2026-10-05 10:00:00 或 ISO，按年月前缀比较
export function isSameYearMonth(
  dateText: string | null | undefined,
  year: number,
  month: number,
): boolean {
  if (!dateText) return false
  return dateText.slice(0, 7) === `${year}-${String(month).padStart(2, '0')}`
}

export function resolveNextStep(
  context: NextStepContext,
  user: User | null | undefined,
  facts: NextStepFacts,
): NextStepGuideModel | null {
  if (context === 'transactions') {
    if (
      navPermitted(user, ['exception:view', 'matching:view']) &&
      facts.todoCount !== null &&
      facts.todoCount > 0
    ) {
      return {
        id: 'transactions-todo',
        text: `流水已就绪，还有 ${facts.todoCount} 笔回款待核销`,
        link: { to: '/exceptions', label: '去处理 →' },
      }
    }
    if (
      navPermitted(user, 'reconciliation:view') &&
      facts.dayOfMonth >= RECONCILIATION_REMINDER_DAY &&
      facts.hasMonthlyReconciliation === false
    ) {
      return {
        id: 'transactions-reconciliation',
        text: '月底了，本月财务对账还没做',
        link: { to: '/reconciliation', label: '去对账 →' },
      }
    }
    return null
  }
  if (context === 'exceptions') {
    // 待办未清零时本页就是处理场所，不再显示引导条
    if (facts.todoCount === null || facts.todoCount > 0) return null
    if (
      navPermitted(user, 'reconciliation:view') &&
      facts.dayOfMonth >= RECONCILIATION_REMINDER_DAY &&
      facts.hasMonthlyReconciliation === false
    ) {
      return {
        id: 'exceptions-reconciliation',
        text: '今日待办已清零，本月财务对账还没做',
        link: { to: '/reconciliation', label: '去对账 →' },
      }
    }
    if (
      navPermitted(user, 'report:view') &&
      facts.dayOfMonth >= REPORT_REMINDER_DAY &&
      facts.hasMonthlyReport === false
    ) {
      return {
        id: 'exceptions-monthly-report',
        text: '本月月报还没生成',
        link: { to: '/reports', label: '去生成 →' },
      }
    }
    return null
  }
  if (facts.hasMonthlyReconciliation === false) {
    return {
      id: 'reconciliation-run',
      text: '本月还没运行对账，点击右上角「运行对账」开始',
      link: null,
    }
  }
  if (
    facts.hasMonthlyReconciliation === true &&
    navPermitted(user, 'report:view') &&
    facts.dayOfMonth >= REPORT_REMINDER_DAY &&
    facts.hasMonthlyReport === false
  ) {
    return {
      id: 'reconciliation-monthly-report',
      text: '对账已完成，本月月报还没生成',
      link: { to: '/reports', label: '去生成 →' },
    }
  }
  return null
}
