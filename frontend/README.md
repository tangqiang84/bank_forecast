# bank_forecast frontend

Vue 管理台。

## 启动

现金预测功能依赖 analytics 分析服务，因此本地开发需要按以下顺序启动三个服务。

先启动分析服务：

```bash
cd ../analytics
uv sync --extra dev
uv run python -m bank_forecast_analytics
```

默认地址：`http://localhost:8001`。

再启动后端并配置本地开发账号：

```bash
cd ../backend
DEV_ADMIN_PASSWORD=123123 JWT_SECRET=local-development-jwt-secret-please-change ./mvnw spring-boot:run
```

再启动前端：

```bash
pnpm install
VITE_BACKEND_BASE_URL=http://localhost:8080 pnpm dev
```

前端只配置浏览器需要访问的 backend 地址，不配置 analytics 地址。analytics 由 backend 通过 `ANALYTICS_BASE_URL` 在服务端调用。

默认地址：`http://localhost:5173`，前端使用 Hash 路由，例如 `http://localhost:5173/#/dashboard`。
登录名：`finance01`，密码为启动后端时设置的 `DEV_ADMIN_PASSWORD`。如果 Vite 自动切换到 `5174` 或 `5175`，默认开发 CORS 配置同样支持这些端口。

如果未启动 analytics，现金预测任务会按设计记录为失败，并提示“分析服务暂不可用，预测任务已记录为失败，可稍后重试”；启动 analytics 后可在现金预测页面重试失败任务。

## 当前功能

- 登录资金工作台，路由守卫会将未登录用户引导到登录页。
- 查看数据库驱动的驾驶舱指标和银行账户。
- 通过 Vue Router 访问独立页面：驾驶舱、银行账户、银行流水、导入任务详情、合同应收、项目资金、匹配结果、异常事项、财务对账、报表中心和现金预测。
- 银行账户页恢复银行流水导入预览/确认/失败行重试、账户新增/编辑/销户和闲置盘点；合同应收页恢复合同主数据/应收计划导入和运行回款匹配。
- 项目资金页恢复项目风险规则配置和批量状态更新；匹配结果与异常事项拆分为不同业务工作台，分别提供匹配组人工确认/拒绝和异常分派/备注/处理/关闭/误报/附件操作。
- 报表中心恢复日报、月报、资金体检报告生成、分页任务、结果预览、健康评分/风险项、审计记录和 CSV 下载。
- 账户、流水、合同、项目、匹配结果、异常和报表任务已提供详情抽屉，详情内容按业务信息、关联对象和处理时间轴分区展示，避免直接堆叠原始 JSON。
- 上传 CSV/XLSX 银行流水并查看导入结果；XLSX 支持六家银行工作表识别和借贷金额字段映射。
- 查询已导入的银行流水明细，支持人工分类、解除关联和 CSV 导出。
- 上传合同 CSV 并查看合同应收计划。
- 运行回款匹配并查看未知收款、部分收款和应收未收异常。
- 查看匹配候选依据、匹配组、分配模式和分配明细，并按组人工确认或拒绝。
- 分派、备注、处理完成和关闭异常事项。
- 通过详情入口追溯流水、合同、匹配结果和异常来源，查看审计记录及异常处理日志。
- 报表中心支持生成日报、月报和资金体检报告，查看任务详情、健康评分/风险项、报表审计记录，并下载 UTF-8 BOM CSV 文件。
- 项目资金页面支持项目列表、回款率、风险评分/等级/风险项和项目详情查看。
- 异常中心支持标记误报和上传附件；附件能力当前提供上传，后续补充删除、预览和对象存储。
- 合同、应收计划、匹配结果、异常、账户、流水、项目和报表列表支持服务端分页；列表提供总数、页码、上一页/下一页和 20/50/100 每页条数切换。
- 现金预测工作区支持设置预测天数和历史窗口、生成预测、回填实际金额、查看预测净现金流、预计应收、预计余额、风险等级、风险提示及 MAE/RMSE/平均偏差；失败任务可直接重试并可查看、切换模型版本。
- 财务对账工作台支持财务记录 CSV 导入、日期范围运行、差异类型筛选、服务端分页、运行摘要和差异详情抽屉；现金预测支持独立任务详情路由 `/forecast/jobs/:id`。
- 前端统一 HTTP 服务层负责生成和透传 `X-Trace-Id`、读取返回体 `trace_id`、校验业务 `code`、处理超时、401、网络错误和结构化错误；默认仅对 GET/HEAD/OPTIONS 请求进行最多 2 次退避重试，导入和其他 POST 请求不自动重试。
- 前端权限控制：路由按 `meta.permission` 鉴权，无权限进入 403 提示页；工作台菜单按权限点过滤；操作按钮通过 `v-permission` 指令在无权限时移除；权限点来自 `/auth/me` 返回的实时数据（`loadCurrentUser` 会用最新响应刷新本地缓存）。前端限制不替代后端鉴权。

## 当前限制

- 账户详情已改为请求后端单条详情接口，返回脱敏账户、最近流水、流水数量和审计记录。
- 复杂多对多对账、跨月调节、预测趋势图和模型训练仍待增强。
- 前端已配置 ESLint、Prettier 和基础 Playwright E2E。
- 所有正式路由已配置唯一名称，路由切换默认回到顶部，浏览器前进/后退恢复滚动位置，锚点链接定位到目标元素。

## 代码检查与 E2E

```bash
pnpm lint
pnpm lint:fix
pnpm format
pnpm format:check
pnpm typecheck
pnpm test
pnpm build
pnpm exec playwright install chromium
pnpm e2e
CI=1 pnpm e2e
```

`pnpm lint` 只执行 ESLint 检查，不修改工作区文件；需要自动修复时使用 `pnpm lint:fix`，执行后必须复核完整 diff。

`pnpm format` 使用 Prettier 格式化整个工作区；`pnpm format:check` 只检查不修改，可用于 CI。Prettier 配置在 `.prettierrc.json`（2 空格缩进、单引号、无分号、行宽 100），与 ESLint 的冲突由 `eslint-config-prettier` 消解。Vue 模板中不得使用 `page--; load()` 这类多语句内联事件处理器，应改为方法引用，否则 `semi: false` 下格式化会产生非法表达式。

Playwright 配置位于 `playwright.config.ts`，会自动启动 `127.0.0.1:5173` 的 Vite 服务。非 CI 本地运行使用已安装的 Chrome channel；`CI=1` 时使用 Playwright `1.63.0` 自带的 Chromium 1243，不依赖本机 Chrome。CI 或本地 CI 模式首次运行前，执行 `pnpm exec playwright install chromium` 下载对应浏览器依赖。`CI=1 pnpm e2e` 仅用于本地模拟 CI 配置，不等同于远程 CI 流水线验证。

前端只在浏览器本地保存访问令牌和当前用户信息，业务数据保存在后端数据库。

银行流水导出、报表下载和异常附件预览已统一通过 HTTP 服务层处理，业务 service 不直接调用裸 `fetch`。

HTTP 请求默认超时 10 秒。可通过 `timeoutMs` 覆盖单次请求超时，通过 `retries` 和 `retryDelayMs` 调整幂等请求的重试次数与初始退避时间。401 会清理当前会话并返回登录页；超时、网络、HTTP、业务错误和用户主动取消会通过 `HttpError.kind` 区分。
