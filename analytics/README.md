# bank_forecast analytics

Python 数据分析与预测服务。

## 启动

```bash
uv sync --extra dev
uv run python -m bank_forecast_analytics
```

默认地址：`http://localhost:8001`

## 接口契约

健康检查：`GET /health`

现金流预测：`POST /forecast/cashflow`

请求示例：

```json
{
  "history": [120000.0, 128000.0, 133000.0],
  "horizon": 7,
  "window_size": 3
}
```

成功响应的 `data` 包含：

- `method`：预测方法名称，目前为 `moving-average-with-trend`。
- `baseline`：历史窗口基线。
- `trend_step`：趋势步长。
- `forecast_values`：按预测天数返回的预测净现金流数组。

Java 后端负责租户数据汇总、任务状态管理和结果落库；本服务只负责预测计算。当前算法是可运行的 MVP，不代表生产级模型，模型评估、版本管理和实际值回填需在后续阶段建设。
