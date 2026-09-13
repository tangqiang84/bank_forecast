# analytics AGENTS.md — Python 分析服务开发规则

本文件继承仓库根目录 `AGENTS.md`，仅补充 Python 分析服务专项规则。

## 1. 范围和技术基线

适用于 `bank_forecast/analytics`。

当前技术栈：

- Python `>=3.11`
- FastAPI
- Uvicorn
- Pandas
- uv
- pytest

版本以 `pyproject.toml` 和 `uv.lock` 为准。

常用命令：

```bash
uv sync
uv run pytest
uv run python -m bank_forecast_analytics
curl http://localhost:8001/health
curl http://localhost:8001/models
```

统一使用 uv 管理依赖和运行环境。

Ruff、mypy、pip-audit 和 bandit 是否为强制检查，以 `pyproject.toml` 和 CI 配置为准。未配置或未执行时，不得描述为检查通过。

## 2. Python 代码规则

- 公开函数和方法必须有类型标注。
- 优先使用 Pydantic 模型或明确数据类。
- 禁止无理由使用 `Any`、`# type: ignore` 或动态结构。
- 日志使用 `logging`。
- 禁止交付 `print` 调试代码。
- 不得暴露 Python 堆栈、服务器路径或内部实现细节。

## 3. 接口和输入校验

服务接口包括：

- `GET /health`
- `GET /models`
- `GET /forecast/sample`
- `POST /forecast/cashflow`

`/forecast/sample` 仅用于样例或联调展示，不代表真实预测任务已执行。

`POST /forecast/cashflow` 的路径、字段、响应、错误码、HTTP 状态码和限制以 `docs/接口契约.md` 为准。

实现必须：

- 使用 Pydantic 校验请求。
- 统一处理 `RequestValidationError`。
- 拒绝空 `history`。
- 拒绝非法 `horizon` 和 `window_size`。
- 拒绝 NaN、正无穷、负无穷和不可序列化数值。
- 限制请求体大小、history 长度和单值范围。
- 拒绝 `window_size` 大于 history 长度。
- 验证模型版本存在且处于 active 状态。
- 保证预测结果长度等于 `horizon`。
- 返回统一结构、稳定错误码和 `trace_id`。
- 不返回堆栈、路径或内部实现细节。

## 4. 模型和服务边界

- 只接受已注册且可用的模型版本。
- 未知模型版本返回稳定错误码。
- 非 active 模型不得接受新的预测请求。
- backend 使用的模型版本必须与 analytics 可用版本一致。
- 预测结果必须记录算法方法和模型版本。
- 算法版本、输入、输出或计算口径变化时，必须增加或更新模型版本，并同步所有受影响的服务、接口、测试和变更记录。

analytics 不负责：

- 登录、权限和租户隔离；
- 业务任务状态和结果持久化；
- 历史任务查询；
- 模型发布审批和回滚；
- 生产资源和部署编排。

## 5. 访问、配置和启动

- frontend 只能调用 backend，不得直接调用 analytics。
- analytics 默认只接受 backend 的受控调用。
- 不得信任前端传入的租户 ID、用户 ID 或权限信息。
- 健康检查可以匿名访问，但不得返回业务数据。
- 生产环境不得将 analytics 业务接口直接暴露到公网。
- 预测接口必须受服务身份保护。
- backend 传入的 `X-Trace-Id` 必须关联到响应 `trace_id`；未传入时由 analytics 生成。
- analytics 不直接连接业务数据库、对象存储或前端。

配置规则：

- 配置通过环境变量或部署配置读取。
- `.env` 不得提交，必须提供不含真实凭证的 `.env.example`。
- 依赖通过 uv 管理，并提交 `uv.lock`。
- 测试默认不得访问真实外部服务。
- 真实联调使用独立测试环境和测试数据。
- 开发、测试和生产配置必须隔离。
- 生产环境必须关闭 reload、禁用 debug，并通过部署配置明确 worker、超时、并发和资源限制。
- 目标环境未实际验证前，不得声称生产启动配置已完成。

## 6. 分析服务测试和交付

根据影响范围执行：

```bash
uv sync
uv run pytest
```

必要时执行：

```bash
uv run ruff check .
uv run ruff format --check .
uv run mypy src
uv run pip-audit
uv run bandit -r src
```

交付前确认输入边界、错误响应、结果长度、健康检查、真实接口、模型版本、backend 联调、敏感信息和调试代码。未执行的检查、工具缺失、网络失败和剩余风险必须如实说明。
