import type { User } from '../services/auth'
import { hasPermission } from './permission'

// 分组导航（六组 14 项；菜单结构见 docs/菜单流程化重设计方案.md §3，P6-2 六组重构与文案任务化）
// 呈现形态：2026-10-21 起为「顶栏一级 tab + 当前组二级条」，见 docs/顶部导航改造方案.md（原为左侧边栏）
// 统一导入向导 /imports（P6-3）位于「第一步 · 数据接入」组首位
export type NavItem = { path: string; label: string; permission: string | string[] }
export type NavGroup = { label: string; items: NavItem[] }

// 导入向导入口权限：任一导入相关权限点可见，页内卡片再按具体导入权限过滤。
export const IMPORTS_WIZARD_PERMISSION = [
  'import:view',
  'transaction:import',
  'contract:import',
  'project:import',
  'receipt:import',
  'reconciliation:run',
]

export const NAV_GROUPS: NavGroup[] = [
  {
    label: '第一步 · 数据接入',
    items: [
      { path: '/imports', label: '导入向导', permission: IMPORTS_WIZARD_PERMISSION },
      { path: '/accounts', label: '银行账户管理', permission: 'account:view' },
      { path: '/templates', label: '导入模板配置', permission: 'template:manage' },
    ],
  },
  {
    label: '第二步 · 回款处理',
    items: [
      {
        path: '/exceptions',
        label: '待办处理',
        permission: ['exception:view', 'matching:view'],
      },
      { path: '/receivables', label: '合同与应收', permission: 'contract:view' },
    ],
  },
  {
    label: '第三步 · 财务对账',
    items: [{ path: '/reconciliation', label: '财务对账', permission: 'reconciliation:view' }],
  },
  {
    label: '经营洞察',
    items: [
      { path: '/dashboard', label: '管理驾驶舱', permission: 'dashboard:view' },
      { path: '/reports', label: '资金报表', permission: 'report:view' },
      { path: '/forecast', label: '现金流预测', permission: 'forecast:view' },
      { path: '/accounts/analysis', label: '账户分析', permission: 'account:view' },
    ],
  },
  {
    label: '基础资料',
    items: [
      { path: '/projects', label: '项目资金', permission: 'project:view' },
      { path: '/transactions', label: '流水查询', permission: 'transaction:view' },
    ],
  },
  {
    label: '系统设置',
    items: [
      { path: '/rules', label: '规则中心', permission: 'project:rule' },
      { path: '/system', label: '用户与权限', permission: 'system:manage' },
    ],
  },
]

export function navPermitted(user: User | null | undefined, permission: string | string[]) {
  return ([] as string[]).concat(permission).some((code) => hasPermission(user, code))
}

/** 登录默认落地页（菜单流程化重设计方案 §5；P6-5 工作台首页上线前维持现状）：按角色取向，无权限回退第一个可见菜单项 */
export function landingPath(user: User | null | undefined): string {
  const roles = user?.roles ?? []
  const preferred = roles.includes('CASHIER')
    ? '/exceptions'
    : roles.includes('BUSINESS')
      ? '/projects'
      : roles.includes('ADMIN')
        ? '/system'
        : '/dashboard'
  const items = NAV_GROUPS.flatMap((group) => group.items)
  const target = items.find((item) => item.path === preferred)
  if (target && navPermitted(user, target.permission)) return target.path
  const fallback = items.find((item) => navPermitted(user, item.permission))
  return fallback ? fallback.path : '/403'
}
