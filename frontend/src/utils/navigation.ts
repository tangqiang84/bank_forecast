import type { User } from '../services/auth'
import { hasPermission } from './permission'

// 侧边栏分组导航（docs/菜单与角色导航重组方案）；P5-4「操作记录」加入系统管理组
export type NavItem = { path: string; label: string; permission: string | string[] }
export type NavGroup = { label: string; items: NavItem[] }

export const NAV_GROUPS: NavGroup[] = [
  {
    label: '经营总览',
    items: [
      { path: '/dashboard', label: '驾驶舱', permission: 'dashboard:view' },
      { path: '/forecast', label: '现金预测', permission: 'forecast:view' },
      { path: '/reports', label: '报表中心', permission: 'report:view' },
      { path: '/accounts/analysis', label: '账户分析', permission: 'account:view' },
    ],
  },
  {
    label: '日常处理',
    items: [
      { path: '/transactions', label: '银行流水', permission: 'transaction:view' },
      {
        path: '/exceptions',
        label: '回款处理工作台',
        permission: ['exception:view', 'matching:view'],
      },
      { path: '/reconciliation', label: '财务对账', permission: 'reconciliation:view' },
    ],
  },
  {
    label: '业务台账',
    items: [
      { path: '/accounts', label: '银行账户', permission: 'account:view' },
      { path: '/receivables', label: '合同应收', permission: 'contract:view' },
      { path: '/projects', label: '项目资金', permission: 'project:view' },
    ],
  },
  {
    label: '配置中心',
    items: [
      { path: '/rules', label: '规则中心', permission: 'project:rule' },
      { path: '/templates', label: '模板配置', permission: 'template:manage' },
    ],
  },
  {
    label: '系统管理',
    items: [{ path: '/system', label: '系统管理', permission: 'system:manage' }],
  },
]

export function navPermitted(user: User | null | undefined, permission: string | string[]) {
  return ([] as string[]).concat(permission).some((code) => hasPermission(user, code))
}

/** 登录默认落地页（菜单方案 §4）：按角色取向，无权限回退第一个可见菜单项 */
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
