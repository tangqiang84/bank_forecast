import { expect, type Page } from '@playwright/test'

/**
 * 顶栏导航辅助（方向 A，2026-10-21）
 * 二级条只渲染当前组子项后，跨组点击二级链接必须先切换一级 tab。
 * 本辅助把「点一级组 tab → 断言二级链接可见 → 点二级链接」收成一个动作。
 * 仅用于测试，不改生产代码；组归属与 src/utils/navigation.ts 保持一致。
 */
export const NAV_ITEM_GROUP: Record<string, string> = {
  导入向导: '第一步 · 数据接入',
  银行账户管理: '第一步 · 数据接入',
  导入模板配置: '第一步 · 数据接入',
  待办处理: '第二步 · 回款处理',
  合同与应收: '第二步 · 回款处理',
  财务对账: '第三步 · 财务对账',
  管理驾驶舱: '经营洞察',
  资金报表: '经营洞察',
  现金流预测: '经营洞察',
  账户分析: '经营洞察',
  项目资金: '基础资料',
  流水查询: '基础资料',
  规则中心: '系统设置',
  用户与权限: '系统设置',
}

export const NAV_GROUP_LABELS = [
  '第一步 · 数据接入',
  '第二步 · 回款处理',
  '第三步 · 财务对账',
  '经营洞察',
  '基础资料',
  '系统设置',
] as const

/** 切到指定一级组并返回其二级链接定位器（不点击），供可见性断言使用 */
export function showGroup(page: Page, group: string) {
  return page
    .getByRole('button', { name: group })
    .click()
    .then(() => page.getByRole('navigation', { name: '二级导航' }))
}

/** 跨组导航：点一级 tab → 断言目标二级链接可见 → 点击 */
export async function gotoNav(page: Page, itemName: string): Promise<void> {
  const group = NAV_ITEM_GROUP[itemName]
  if (!group) throw new Error(`gotoNav: 未知导航项「${itemName}」，请补 NAV_ITEM_GROUP`)
  await page.getByRole('button', { name: group, exact: true }).click()
  const link = page.getByRole('link', { name: itemName })
  await expect(link).toBeVisible()
  await link.click()
}

/** 断言六组一级 tab 均可见（顶栏形态下替代旧「六组全部展开」断言） */
export async function expectAllGroupTabsVisible(page: Page): Promise<void> {
  for (const group of NAV_GROUP_LABELS) {
    await expect(page.getByRole('button', { name: group, exact: true })).toBeVisible()
  }
}
