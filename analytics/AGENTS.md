# analytics AGENTS.md — Python 分析服务开发规则

## 1. 范围与技术基线

适用于 `bank_forecast/analytics`。当前模块为 FastAPI 分析服务：Python `>=3.11`、uv、Uvicorn、Pandas、pytest。版本以 `pyproject.toml` 为准，源码为 `src/bank_forecast_analytics`，测试为 `tests`。

## 2. 工作原则

- 需求不清时集中澄清，清晰时按计划实施。
- 小步修改并实际运行测试、检查和服务验证。
- 不用 mock 结果冒充真实算法能力，不虚构命令输出。
- analytics 只负责预测/分析计算，不负责登录、权限、租户隔离、业务任务状态或正式数据持久化；这些由 backend 负责。

## 3. 常用命令

```bash
uv sync
uv run pytest
uv run ruff check .
uv run ruff format .
uv run mypy src
uv run python -m bank_forecast_analytics
```

依赖必须通过 uv 管理并提交 `uv.lock`，禁止安装到全局环境。若 Ruff、mypy、pip-audit 或 bandit 尚未在 `pyproject.toml` 配置，不得声称已通过；接入时同步提交配置和 CI。

## 4. Python 代码规范

- 公开函数和方法必须有类型标注，优先使用 Pydantic 模型或明确数据类。
- 禁止无理由使用 `Any`、`# type: ignore` 或动态字符串拼接结构化数据。
- Ruff 负责格式化和 import 排序。
- 日志使用 `logging`，禁止交付 `print` 调试代码。
- 错误不得直接暴露 Python 堆栈、文件路径或内部实现细节。

## 5. 预测接口契约

现金流预测接口：`POST /forecast/cashflow`。

请求至少包含：

```json
{"history":[],"horizon":30,"window_size":7}
```

响应至少包含：

```json
{"method":"baseline","model_version":"v1","baseline":0,"trend_step":0,"forecast_values":[]}
```

`forecast_values` 长度必须等于 `horizon`，数值必须有限且可序列化。修改字段、含义或错误码时，必须同步 backend、frontend、接口文档和测试。

## 6. 输入边界与错误处理

必须验证：空 history、历史长度不足、horizon 非正或超上限、window_size 非法、非数值、NaN/无穷大、负数金额、极大金额和请求体大小。

错误必须结构化，例如：

```json
{"error_code":"INVALID_FORECAST_INPUT","message":"预测参数不合法","details":{"field":"horizon"}}
```

## 7. 模型版本和健康检查

至少提供 `GET /health` 和 `GET /models`。生产禁止 `reload=True`；开发环境可使用。每次算法修改必须更新模型/算法版本、补充测试、记录原因并验证历史输入的影响。结果应可追踪任务 ID、方法、版本、输入范围、参数、生成时间和输出。

## 8. backend 调用约束

backend 调用 analytics 时负责超时、有限重试、TraceID、任务状态和结果保存。analytics 必须区分网络错误、超时、输入错误和算法错误，返回稳定错误码，不要求调用方无限重试。

## 9. 测试与安全

测试不得访问外网、生产数据或依赖执行顺序。至少覆盖正常预测、全部输入边界、算法异常、响应长度/类型、健康检查和模型列表。配置从环境变量读取，`.env` 不提交；日志不得包含完整业务数据、凭证或用户隐私。

交付前实际执行：

- [ ] `uv sync`
- [ ] `uv run pytest`
- [ ] `uv run ruff check .`（已接入时）
- [ ] `uv run mypy src`（已接入时）
- [ ] 健康检查和真实接口验证
- [ ] 模型版本和接口文档已同步
- [ ] 已检查 Git diff 和敏感信息
