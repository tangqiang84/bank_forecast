# bank_forecast backend

Java 主后端。

## 启动

```bash
export DEV_ADMIN_PASSWORD='<本地开发密码>'
export JWT_SECRET='<本地随机长字符串>'
./mvnw spring-boot:run
```

默认地址：`http://localhost:8080`

预测服务地址默认读取 `ANALYTICS_BASE_URL`（默认 `http://localhost:8001`）。连接和读取超时
分别由 `ANALYTICS_CONNECT_TIMEOUT_MS`（默认 2000）和 `ANALYTICS_READ_TIMEOUT_MS`（默认 10000）控制。

## 本地数据库

- 默认使用 H2 文件库：`./data/bank_connector`
- 启动时通过 Flyway 执行 `src/main/resources/db/migration`
- 本地 H2 控制台：`http://localhost:8080/h2-console`

## 开发账号

- 登录名：`finance01`
- 密码：启动前通过 `DEV_ADMIN_PASSWORD` 环境变量设置；未设置时不会初始化默认登录用户
- 租户：演示企业

## 银行流水 CSV 导入

当前接口：`POST /api/v1/imports/bank-statements`

字段：

```csv
transaction_no,transaction_date,direction,amount,balance_after,counterparty_name,summary
TXN-001,2026-09-09,income,128400.00,2865300.00,ACME客户,项目回款
```

`direction` 支持：`income`、`expense`、`transfer`、`refund`、`reversal`。

导入限制由配置控制：默认单文件不超过 10 MB、最多 10000 行、交易日期距当前日期不超过 3650 天、金额最多 2 位小数且不超过 `IMPORT_MAX_AMOUNT`。单行错误不会阻止其他正确行导入，任务摘要会返回成功、失败、跳过数量和错误明细。重复交易号按跳过处理。

## 合同应收 CSV 导入

接口：`POST /api/v1/imports/contracts`，使用 multipart 字段 `file`。

必填字段：

```csv
contract_no,contract_name,customer_name,contract_amount,node_name,node_type,due_date,plan_amount
CT-001,软件实施合同,示例客户,100000.00,验收款,acceptance,2026-10-01,100000.00
```

可选字段：`project_no`、`project_name`、`owner_name`。

合同金额和应收计划金额必须为正数、最多 2 位小数且不超过金额上限；同一合同的应收计划合计不能超过合同金额。重复合同应收节点按跳过处理。

错误明细：`GET /api/v1/imports/{jobId}/errors`；错误 CSV 下载：`GET /api/v1/imports/{jobId}/errors/download`。

## 回款匹配与异常

- `GET /api/v1/contracts`：查询合同。
- `GET /api/v1/contracts/receivables`：查询应收计划。
- `POST /api/v1/matching/receivables/run`：运行回款匹配并生成异常。
- `GET /api/v1/matching/results`：查询匹配结果。
- `POST /api/v1/matching/results/{id}/confirm`：人工确认待确认匹配，并更新流水和应收金额。
- `POST /api/v1/matching/results/{id}/reject`：人工拒绝待确认匹配，可提交 `reason`。
- `GET /api/v1/matching/exceptions`：查询异常事项。
- `POST /api/v1/matching/exceptions/{id}/assign`：分派异常事项，默认分派给当前用户。
- `POST /api/v1/matching/exceptions/{id}/comment`：追加异常备注。
- `POST /api/v1/matching/exceptions/{id}/resolve`：标记异常已处理。
- `POST /api/v1/matching/exceptions/{id}/close`：关闭已处理异常。
- `GET /api/v1/matching/exceptions/{id}/logs`：查询异常操作日志。
- `GET /api/v1/bank-transactions`：按账户、合同编号、项目编号、交易日期和匹配状态分页查询银行流水。
- `GET /api/v1/bank-transactions/{id}`：查看流水、匹配结果和审计日志。
- `GET /api/v1/contracts/{id}`：查看合同、应收节点、关联流水和审计日志。
- `GET /api/v1/matching/results/{id}`：查看匹配结果、原始流水、关联异常和审计日志。
- `GET /api/v1/matching/exceptions/{id}`：查看异常来源和处理日志。
- `GET /api/v1/audit-logs`：按 `target_type`、`target_id`、`action` 分页查询审计日志。

合同、应收计划、匹配结果和异常列表支持分页及业务筛选，返回结构统一为：

```json
{"items": [], "page": 1, "page_size": 20, "total": 0}
```

详情接口用于财务对账追溯，所有查询按当前登录租户隔离；匹配结果保留确认人和确认时间，审计日志保留操作人、TraceID、对象和操作详情。驾驶舱的应收总额、已收金额、逾期未收金额和异常数量均从数据库实时汇总。

当前匹配支持精确匹配、部分收款、未知收款和逾期未收；客户名称比较会统一处理公司后缀、空格和标点，且可通过 `MATCH_CUSTOMER_NAME_MIN_LENGTH` 和 `MATCH_CUSTOMER_NAME_ALLOW_CONTAINS` 配置匹配规则。部分收款候选需人工确认或拒绝，异常事项支持分派、备注、处理、关闭和日志追踪。拆分匹配和合并匹配仍待新增分配明细表后实现。

当前 MVP 尚剩两项匹配能力：一笔流水拆分匹配多个应收节点、多笔流水合并匹配一个应收节点。两者需要统一的匹配分配明细和计账模型后才能开发，现有匹配结果不得用于这两类场景的财务计账。

## 现金流预测

预测任务接口：

- `POST /api/v1/forecast/cashflow/jobs?horizon=7&window_size=3`：按当前租户流水和应收计划创建并执行预测任务；`horizon` 范围为 1-90，`window_size` 范围为 1-30。
- `GET /api/v1/forecast/cashflow/latest`：查询当前租户最近一次成功预测及预测点。
- `GET /api/v1/forecast/cashflow/jobs/{id}`：查询预测任务及结果详情。
- `POST /api/v1/forecast/cashflow/jobs/{id}/retry`：重试失败任务，单个任务最多执行 3 次。
- `POST /api/v1/forecast/cashflow/jobs/{id}/actuals`：按预测日期汇总真实银行净现金流，回填实际金额和偏差。
- `GET /api/v1/forecast/models`：查询模型版本及当前启用状态。
- `POST /api/v1/forecast/models/{version}/activate`：启用指定模型版本。

预测任务和预测点分别落库到 `forecast_job`、`forecast_result`，模型版本落库到 `forecast_model_version`。实际回填后，详情接口中的 `evaluation` 返回评估点数、MAE、RMSE 和平均偏差。预测失败会记录失败状态和错误信息，接口返回结构化错误，不会伪装为成功。所有查询按当前登录租户隔离。

实际值仅回填已发生日期（`forecast_date <= 当前日期`），未来预测点保持空值，不参与评估指标计算。

当前 analytics 使用 `moving-average-with-trend` 算法版本 `v1`。任务创建时绑定当前启用模型版本，模型版本切换不会改变历史任务的版本记录。
