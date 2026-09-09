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
- `model_version`：模型版本，目前为 `v1`。

模型版本查询：`GET /models`，当前返回可用的 `v1` 版本。Java 后端会在任务创建时记录版本，便于后续对不同版本进行评估和回溯。

Java 后端负责租户数据汇总、任务状态管理、实际值回填、评估指标和结果落库；本服务负责预测计算并回传模型版本。当前模型评估采用回填后的 MAE、RMSE 和平均偏差，模型训练和自动择优仍属于后续增强。
