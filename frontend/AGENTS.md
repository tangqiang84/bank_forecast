# frontend AGENTS.md — Vue 3 前端开发规则

## 1. 范围与技术基线

适用于 `bank_forecast/frontend`。当前技术栈为 Vue 3、TypeScript、Vite、Vue Router 4、Vitest、Playwright、ESLint 和 `vue-tsc`。包管理器统一使用 pnpm，必须提交 `pnpm-lock.yaml`，禁止混用 npm、yarn 或 bun。版本以 `package.json` 为准。

## 2. 实际目录

```text
src/components/    公共组件
src/services/      API 和业务服务
src/utils/         通用工具
src/views/         页面级视图
src/router/        路由配置
tests/             Playwright E2E
```

使用 Vue Composition API、`<script setup lang="ts">` 和 composables。不得使用 React Hooks、函数组件、Zustand 或 Redux Toolkit 等不适用方案。

## 3. 工作原则

- 需求清晰时先给简短计划并开工；有实质歧义时集中澄清。
- 按设计、开发、验证、交付小步实施，禁止无关重构。
- 长任务分阶段汇报，真实说明测试结果和剩余风险。
- 禁止使用静态 mock 数据冒充真实业务能力。

## 4. 常用命令和依赖

```bash
pnpm install
pnpm dev
pnpm lint
pnpm lint:fix
pnpm typecheck
pnpm test
pnpm build
pnpm e2e
```

建议脚本为：

```json
{"lint":"eslint . --ext .vue,.js,.ts","lint:fix":"eslint . --ext .vue,.js,.ts --fix"}
```

新增依赖用 `pnpm add`，同步依赖用 `pnpm install`。依赖变更后运行 `pnpm audit`；registry 网络失败必须记录真实原因，不得写成审计通过。

## 5. TypeScript、API 和错误处理

- TypeScript 必须 strict；禁止无理由使用 `any`、`@ts-ignore` 或 `@ts-expect-error`。
- API 调用集中在 `src/services`，统一处理 Base URL、Token、超时、结构化错误、分页和重试。
- 页面不得直连数据库、analytics 或重复实现请求错误处理。
- 加载、空数据、失败和重试状态必须明确；后端异常堆栈不得直接展示给用户。
- 密钥、数据库凭证和生产 Token 不得进入前端；环境变量使用 `VITE_` 前缀并提供 `.env.example`。

## 6. 路由和页面

- 正式页面必须通过 Vue Router 访问，所有路由有唯一 `name`。
- 编程式跳转必须使用命名路由，业务 ID 使用 `params`，筛选和分页使用 `query`。
- `/` 或 `/#/` 必须可达；未登录访问业务页跳转登录，登录后返回原目标页。
- 刷新、前进、后退和滚动行为必须正确。
- 只有存在未保存导入配置、规则编辑或表单时才使用 `onBeforeRouteLeave` 防丢失。

## 7. 功能守恒和业务交互

重构前必须对比 PRD、当前/历史实现、API、权限、测试和 E2E。除非明确删除，不得删除或降级：

- 银行账号导入和详情；
- 合同应收导入；
- 项目资金规则设置；
- 匹配结果确认/拒绝；
- 异常关闭/重开；
- 财务对账和现金预测详情；
- 导入/预测任务详情、失败重试；
- 报表生成、预览和下载。

禁止用通用列表替代业务工作台，禁止把匹配结果和异常处理做成同一页面，禁止以 JSON 或 `<pre>` 替代正式详情面板。正式页面应根据场景提供筛选、服务端分页、抽屉、时间轴、状态、操作记录、加载/空/失败状态和用户可理解反馈。

导入流程至少包括文件校验、预览、确认、任务状态、成功结果、失败原因和重试/重新导入。

## 8. 列表分页

所有列表使用服务端分页，不得通过 `page_size=100` 一次性加载全量数据。请求参数为 `page`、`page_size`，响应包含 `items`、`page`、`page_size`、`total`；默认 20 条、最大 100 条。筛选变化时重置页码，并同步更新接口、测试和交互文档。

## 9. 测试和 E2E

单元测试使用 Vitest 和 Vue Test Utils，覆盖校验、service 转换、加载/空/失败、分页、路由守卫和核心业务动作。

Playwright 配置为 `playwright.config.ts`，测试放在 `tests/*.spec.ts`。默认使用 Playwright Chromium，`webServer` 启动本地 Vite，不依赖本机浏览器状态或真实生产凭证。优先使用语义定位和稳定 `data-testid`，禁止脆弱 CSS 层级选择器。

跳转测试必须依次断言：触发跳转、URL、目标页面核心骨架。核心 E2E 至少覆盖首页、登录成功/失败、两类导入、规则设置、匹配处理、异常闭环、报表生成/下载、详情抽屉/时间轴、预测失败提示和重试。

## 10. UI 验证和交付

涉及 UI 的改动必须实际启动并检查桌面/移动端、核心点击路径、刷新、前进后退、网络请求、控制台错误、Vue warning 和静态资源 404。

交付前实际执行：

- [ ] `pnpm lint`
- [ ] `pnpm typecheck`
- [ ] `pnpm test`
- [ ] `pnpm build`
- [ ] 涉及页面时执行 `pnpm e2e`
- [ ] 相关接口、测试、E2E、PRD/交互文档已同步
- [ ] 无敏感信息、调试代码和无关改动
