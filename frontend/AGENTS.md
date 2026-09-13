# frontend AGENTS.md — Vue 3 前端开发规则

本文件继承仓库根目录 `AGENTS.md`，仅补充 Vue 3 前端专项规则。

## 1. 范围和技术基线

适用于 `bank_forecast/frontend`。

当前技术栈：

- Vue 3；
- TypeScript；
- Vite；
- Vue Router 4；
- Vitest；
- Vue Test Utils；
- Playwright；
- ESLint；
- `vue-tsc`。

版本以 `package.json` 和 `pnpm-lock.yaml` 为准。

主要目录：

```text
src/components/    公共组件
src/services/      API 和业务服务
src/utils/         通用工具
src/views/         页面级视图
src/router.ts      路由配置
tests/             Playwright E2E
```

使用 Composition API、`<script setup lang="ts">` 和 composables。不得使用 React Hooks、React 组件、Zustand 或 Redux Toolkit。

## 2. 包管理和命令

统一使用 pnpm，禁止混用 npm、yarn、bun 或其他包管理器。

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

规则：

- 新增依赖必须使用 pnpm，并同步提交 `pnpm-lock.yaml`。
- `pnpm lint` 只检查，不修改工作区。
- `pnpm lint:fix` 仅在明确需要时使用，执行后必须复核 diff。
- 依赖变更后执行 `pnpm audit`。
- registry 网络失败时记录真实错误和风险结论。
- 不得通过修改脚本、测试配置或 CI 配置绕过失败。

## 3. TypeScript 和 Vue

- TypeScript 使用 strict 模式。
- 优先使用明确类型、接口和联合类型。
- 禁止无理由使用 `any`、`@ts-ignore` 或 `@ts-expect-error`。
- Vue 组件使用 `<script setup lang="ts">`。
- 页面、组件、service、composable 和类型职责清晰。
- API 响应必须使用明确类型，Promise 必须有错误处理。
- 变量、函数和文件名必须清晰可读。
- 错误提示必须是用户可理解的中文。
- 不得把 Java、Python 堆栈或内部错误直接展示给用户。

## 4. API、环境变量和安全

API 规则：

- frontend 只能调用 backend，不得直接调用 analytics。
- 所有业务请求必须通过统一 HTTP 服务层或其适配层。
- 业务 service 不得直接使用裸 `fetch`。
- `src/services/http.ts` 内部可以使用原生 `fetch`。
- Token、租户 Header 和 TraceID 必须由统一请求层传递。
- 接口字段、错误码、分页、超时、重试和响应格式以 `docs/接口契约.md` 为准。

环境变量规则：

- 前端环境变量必须使用 `VITE_` 前缀。
- backend 地址使用 `VITE_BACKEND_BASE_URL`。
- 前端不得读取、保存或调用 analytics 地址。
- `.env.example`、构建配置和发布产物不得包含 `VITE_ANALYTICS_BASE_URL`。
- analytics 地址只能配置在 backend 的 `ANALYTICS_BASE_URL`。
- `.env.example` 不得包含真实账号、密码、Token 或内部凭证。

凭证和数据规则：

- 当前访问令牌和当前用户信息按现有实现保存在 `localStorage`。
- 不得在浏览器存储中保存密码、刷新 Token、API Key、数据库密码、服务间 Token 或私钥。
- 不得在日志、错误提示、页面文本或测试输出中打印 Token。
- 不得输出完整 API 响应、完整银行账号、客户隐私信息或原始导入文件内容。
- 文件预览和下载必须使用 backend 返回的受控资源。
- 前端权限控制不能替代 backend 鉴权。

## 5. 路由和页面

正式页面必须通过 Vue Router 访问。

- 每个正式路由必须配置唯一的 `name`。
- 编程式跳转优先使用命名路由。
- 业务 ID 使用 `params`，筛选、排序和分页使用 `query`。
- 需要登录的页面必须由路由守卫保护。
- 未登录访问业务页面时跳转登录页。
- 登录成功后返回原始目标页面。
- 页面刷新后保持正确路由并恢复会话。
- 会话失效时清理本地会话并跳转登录页。
- `/` 或 `/#/` 必须可达。
- 路由切换默认回到顶部，浏览器前进和后退恢复历史滚动位置。

新增或修改路由时，应校验路由参数、逐步迁移硬编码路径并补充页面跳转 E2E。

## 6. 页面质量和功能守恒

页面、路由、组件或 service 重构时，不得无意删除、降级或隐藏已有业务能力。具体功能以 `docs/功能清单.md`、PRD 和 `docs/ui/` 为准。

禁止用通用列表、原始 JSON、静态 mock、占位按钮或空白面板替代正式业务能力。

正式页面必须处理加载、空数据、请求失败、成功、失败、可重试、无权限和数据不存在状态。

涉及列表、详情或任务时，应根据业务需要提供筛选、服务端分页、业务字段、详情或抽屉、关联对象、时间轴、防重复提交、任务状态和明确反馈。

## 7. 测试和 E2E

- 页面改动必须补充或调整对应测试。
- 测试覆盖与改动相关的成功、失败、空数据、权限、边界和重试场景。
- 核心链路必须执行真实集成 E2E 或隔离 E2E，并明确测试边界。
- 隔离 E2E 可以 mock backend，但不得证明真实服务可用。
- 真实集成 E2E 使用独立数据库、测试租户和专用账号，不得使用生产凭证。
- 测试不得依赖历史 Cookie、缓存、本机登录状态或执行顺序。

Playwright 规则：

- CI 使用 Playwright 自带 Chromium，不使用本机 Chrome channel。
- 本地非 CI 模式可以使用已安装的 Chrome channel。
- `CI=1` 或 `CI=true` 视为 CI 模式；`CI=0`、空值或未设置视为本地模式。
- 不得使用 `Boolean(process.env.CI)` 判断 CI。
- Playwright 版本、Chromium revision、安装步骤和远程 CI 准入以 `docs/发布检查清单.md` 为准。
- 本地 `CI=1 pnpm e2e` 不等于远程 CI 已通过。

## 8. 展示、样式和交付

- 日期、金额和状态使用统一展示规则。
- 前端不得使用浮点数执行资金业务计算，金额以 backend 返回结果为准。
- 状态、风险等级和异常类型映射为中文展示。
- 遵循现有样式约定，避免遮挡、溢出和布局跳动。
- 表单、按钮、键盘操作、弹窗焦点和表格表头应满足基本可访问性要求。
- 具体页面验收以 `docs/ui/` 和测试文档为准。

根据任务影响范围执行：

```bash
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

涉及页面、路由或核心交互时执行：

```bash
pnpm e2e
```

交付前确认：

- 代码、接口、测试和相关文档已同步；
- 已检查敏感信息、调试代码、死代码和无关变更；
- 已执行 `git diff --check`；
- 未执行的检查、配置限制和剩余风险已说明；
- 未将隔离 E2E 描述为真实集成验证；
- 未将本地 CI 模式描述为远程 CI 已通过；
- 未将整改目标描述为当前已完成能力。
