# bank_forecast

银行资金智能连接器实现骨架。

## 运行要求

- 后端：JDK 8
- 分析服务：Python 3.11+
- 前端：Node.js 20+ / pnpm

## 子工程

- `backend/` Java 主后端
- `analytics/` Python 解析与辅助分析
- `frontend/` Vue 驾驶舱与工作台

## 启动顺序

1. `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home PATH="$JAVA_HOME/bin:$PATH" ./mvnw spring-boot:run`
2. `cd analytics && uv sync && uv run python -m bank_forecast_analytics`
3. `cd frontend && pnpm install && pnpm dev`

前端默认端口为 `5173`，若占用会自动切换到下一个可用端口。

本地开发登录：启动后端时设置 `DEV_ADMIN_PASSWORD=123123`，使用账号 `finance01` 登录；该密码只用于开发验证，不应带入生产配置。

## 约定

- 后端负责业务 API、鉴权、统一响应、审计
- 分析服务负责解析、清洗、辅助计算和批处理
- 前端负责查询、录入、看板和异常处理展示

## 分析与预测服务

后端通过 `ANALYTICS_BASE_URL` 调用 analytics 服务生成现金流预测，默认地址为
`http://localhost:8001`。可通过以下环境变量调整连接超时：

- `ANALYTICS_CONNECT_TIMEOUT_MS`：连接超时，默认 2000 毫秒。
- `ANALYTICS_READ_TIMEOUT_MS`：读取超时，默认 10000 毫秒。

analytics 服务接口契约为 `POST /forecast/cashflow`，请求包含历史每日净现金流
`history`、预测天数 `horizon` 和历史窗口 `window_size`，返回预测算法、基线、趋势步长及
`forecast_values`。后端会从当前租户的银行流水汇总历史净现金流，并叠加未来应收计划形成预测展示数据。

## 本阶段功能

- 合同、项目和合同应收计划数据库迁移。
- 合同 CSV 导入：支持自动创建项目、合同和应收节点。
- 流水 CSV/XLSX 导入：XLSX 支持六家银行工作表识别和借贷金额字段映射。
- 流水与应收匹配：支持合同编号或客户名称、金额和日期窗口匹配，并支持一笔流水拆分到多个应收节点、多笔流水合并到一个应收节点。
- 统一计账模型：普通匹配、拆分匹配、合并匹配均通过分配明细记录计账。
- 异常生成：支持未知收款、部分收款和应收未收异常。
- 现金流预测：支持预测任务创建、结果落库、最新结果查询、失败重试和风险提示。

## MVP 当前剩余任务

当前已完成核心匹配、导入质量、财务追溯、异常闭环、预测接入和预测增强；一笔流水拆分匹配多个应收节点、多笔流水合并匹配一个应收节点已纳入统一分配明细模型。

仍需补齐的 MVP 能力：

- Excel/6 家银行模板识别和字段映射已完成基础版；六家银行组合样本已完成真实 XLSX 回放验证，真实银行导出文件扩充和导入预览/确认导入仍待补齐。
- 银行账户新增、编辑、销户标记、账户盘点和 30/90/180 天闲置识别。
- 流水分类、解除关联、导出，以及财务记录导入和对账差异识别。
- 报表中心、日报/月报、资金体检报告和 PDF/Excel 导出。
- 项目资金页面、项目风险概览、异常误报关闭和附件上传。

安全与投产准备暂不纳入本轮 MVP；规则配置、回单、通知、模型训练、自动择优和更复杂的预测评估属于后续增强。
