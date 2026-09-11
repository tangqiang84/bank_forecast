import { expect, test } from '@playwright/test'

test('首页可以成功渲染登录入口', async ({ page }) => {
  await page.goto('/')

  await expect(page).toHaveTitle('银行资金智能连接器')
  await expect(page.getByRole('heading', { name: '登录资金工作台' })).toBeVisible()
  await expect(page.getByRole('button', { name: '登录' })).toBeVisible()
})
