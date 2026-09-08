# bank_forecast

银行资金智能连接器实现骨架。

## 运行要求

- 后端：JDK 21
- 分析服务：Python 3.11+
- 前端：Node.js 20+ / pnpm

## 子工程

- `backend/` Java 主后端
- `analytics/` Python 解析与辅助分析
- `frontend/` Vue 驾驶舱与工作台

## 启动顺序

1. `cd backend && JAVA_HOME=... PATH="$JAVA_HOME/bin:$PATH" ./mvnw spring-boot:run`
2. `cd analytics && uv sync && uv run python -m bank_forecast_analytics`
3. `cd frontend && pnpm install && pnpm dev`

前端默认端口为 `5173`，若占用会自动切换到下一个可用端口。

## 约定

- 后端负责业务 API、鉴权、统一响应、审计
- 分析服务负责解析、清洗、辅助计算和批处理
- 前端负责查询、录入、看板和异常处理展示
