# frontend 开发规则

## 范围

本文件适用于 `bank_forecast/frontend` 下的 Vue 3 + TypeScript 前端模块。

## 常用命令

所有命令在本目录执行：

```bash
pnpm install
pnpm lint
pnpm typecheck
pnpm test
pnpm build
pnpm e2e
```

## 代码检查

- 使用 ESLint 检查 `.vue`、`.js`、`.ts` 文件，`pnpm lint` 会自动修复可修复问题。
- 修改路由、组件、service 或测试后，至少运行 `pnpm lint`、`pnpm typecheck`、`pnpm test` 和 `pnpm build`。
- 涉及页面流程时运行 `pnpm e2e`。

## E2E 约定

- Playwright 配置文件为 `playwright.config.ts`。
- E2E 用例放在 `tests/` 目录，文件名使用 `*.spec.ts`。
- Playwright webServer 使用 `pnpm exec vite --host 127.0.0.1 --port 5173 --strictPort` 启动本地前端。
- 基础渲染测试不依赖后端真实登录；需要后端数据的用例必须明确启动后端并使用真实接口响应。
