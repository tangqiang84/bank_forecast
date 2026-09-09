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

## 约定

- 后端负责业务 API、鉴权、统一响应、审计
- 分析服务负责解析、清洗、辅助计算和批处理
- 前端负责查询、录入、看板和异常处理展示

## 本阶段功能

- 合同、项目和合同应收计划数据库迁移。
- 合同 CSV 导入：支持自动创建项目、合同和应收节点。
- 流水与应收匹配：支持合同编号或客户名称、金额和日期窗口匹配。
- 异常生成：支持未知收款、部分收款和应收未收异常。
