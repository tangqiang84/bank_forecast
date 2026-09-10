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
- 密码：开发环境启动前通过 `DEV_ADMIN_PASSWORD` 环境变量设置，例如 `DEV_ADMIN_PASSWORD=123123`；已有本地数据库也会在启动时同步该开发密码
- 租户：演示企业

## 银行账户管理

- `GET /api/v1/bank-accounts`：返回账户列表、脱敏账号后四位、最近动账日期、闲置天数和闲置级别。
- `POST /api/v1/bank-accounts`：新增银行账户。
- `PUT /api/v1/bank-accounts/{id}`：编辑账户基础信息和余额，编辑时需提交完整账号，接口不返回明文账号。
- `POST /api/v1/bank-accounts/{id}/close`：标记账户已销户，不物理删除历史流水。
- `POST /api/v1/bank-accounts/idle-scan`：按最近流水执行账户盘点；30 天、90 天、180 天分别返回对应闲置级别，状态同步为 `idle` 或 `active`。

当前账号存储为本地开发演示方案，页面和接口只返回后四位；正式投产前需替换为合规密钥管理和加密存储方案。

## 银行流水 CSV / Excel 导入

当前接口：`POST /api/v1/imports/bank-statements`

支持 `.csv` 和 `.xlsx`。XLSX 工作簿可包含多个工作表，系统会按工作表名称识别中国银行、工商银行、广发银行、平安银行、上海银行、苏州银行，并在说明行之后自动定位交易表头。导入结果中的 `recognized_templates` 返回每个工作表的识别状态、银行名称和映射说明。

字段：

```csv
transaction_no,transaction_date,direction,amount,balance_after,counterparty_name,summary
TXN-001,2026-09-09,income,128400.00,2865300.00,ACME客户,项目回款
```

`direction` 支持：`income`、`expense`、`transfer`、`refund`、`reversal`。

六家银行样本中常见的“交易日期、记账日期、摘要、借方金额、贷方金额、余额、对方户名、凭证号/流水号”等字段会映射为统一流水字段。借方金额映射为支出，贷方金额映射为收入；交易流水号缺失时生成工作表行号标识并保留原始字段。未知工作表或找不到交易日期与借/贷金额表头时不会导入该工作表；若整个工作簿均未识别，接口返回结构化错误。

导入限制由配置控制：默认单文件不超过 10 MB、最多 10000 行、交易日期距当前日期不超过 3650 天、金额最多 2 位小数且不超过 `IMPORT_MAX_AMOUNT`。单行错误不会阻止其他正确行导入，任务摘要会返回成功、失败、跳过数量和错误明细。重复交易号按跳过处理。

## 财务记录导入与对账

- `POST /api/v1/imports/finance-records`：导入财务收付款 CSV，必填列为 `record_no`、`record_type`、`record_date`、`amount`；支持 `receipt`、`payment`、`voucher`、`journal`。
- `POST /api/v1/reconciliation/run`：按可选日期范围执行银行账/财务账对账。收款匹配 `income`，付款匹配 `expense`，金额一致且日期相差不超过 3 天；同时优先比较归一化对手方名称。
- `GET /api/v1/reconciliation/results`：查询本次或全部对账差异，支持 `job_id`、`difference_type`、`page`、`page_size`。

对账成功记录写入 `match_result` 并关联 `finance_record_id`；银行有流水但财务无记录生成 `bank_unrecorded`，财务有记录但银行无流水生成 `finance_unmatched`，两类差异均进入异常事项中心并保留任务编号和审计日志。当前为 CSV + 单笔一对一规则，复杂拆分、跨月、科目级和财务系统 API 对接后置。

## 报表中心与文件导出

- `POST /api/v1/reports`：生成 `daily` 日报、`monthly` 月报或 `health` 资金体检报告；`monthly` 通过 `params_json.month` 指定月份。
- `GET /api/v1/reports`：查询当前租户最近报表任务。
- `GET /api/v1/reports/{id}`：查看报表结构化结果和生成状态。
- `GET /api/v1/reports/{id}/download`：下载 UTF-8 BOM CSV 文件。

当前报表为同步 MVP 实现，数据直接读取业务表；暂不生成 PDF/Excel 二进制文件，也未接入对象存储。

## 合同应收 CSV 导入

接口：`POST /api/v1/imports/contracts`，使用 multipart 字段 `file`。

支持两种 CSV 模式。

合同应收计划模式必填字段：

```csv
contract_no,contract_name,customer_name,contract_amount,node_name,node_type,due_date,plan_amount
CT-001,软件实施合同,示例客户,100000.00,验收款,acceptance,2026-10-01,100000.00
```

可选字段：`project_no`、`project_name`、`owner_name`。

合同主数据模式支持项目中的合同样本格式，必填字段为：

```csv
contract_no,customer_name,project_no,project_name,contract_amount,sign_date,status
XC-2026-001,示例客户,PRJ-001,示例项目,1200000.00,2026-05-10,执行中
```

合同主数据模式不要求 `contract_name`、`node_name`、`node_type`、`due_date`、`plan_amount`；系统使用 `project_name` 作为合同名称，只创建合同和项目，不创建应收节点。应收节点可通过合同应收计划模式另行导入。

独立应收计划模式支持 `docs/sample/测试数据/应收计划样本.csv` 的字段结构：

```csv
plan_id,contract_no,customer_name,project_no,node_name,due_date,plan_amount,received_amount,status
```

该模式通过 `contract_no` 关联已存在合同，`contract_amount` 和 `contract_name` 可以不提供；`node_type` 缺失时默认使用 `receivable`。如果合同不存在且文件缺少 `contract_amount`，系统会按行返回明确错误，需要先导入合同主数据或补充合同金额。

合同金额和应收计划金额必须为正数、最多 2 位小数且不超过金额上限；同一合同的应收计划合计不能超过合同金额。重复合同应收节点按跳过处理。

错误明细：`GET /api/v1/imports/{jobId}/errors`；错误 CSV 下载：`GET /api/v1/imports/{jobId}/errors/download`。

## 回款匹配与异常

- `GET /api/v1/contracts`：查询合同。
- `GET /api/v1/contracts/receivables`：查询应收计划。
- `POST /api/v1/matching/receivables/run`：运行回款匹配并生成异常。
- `GET /api/v1/matching/results`：查询匹配结果。
- `GET /api/v1/matching/results/{id}/allocations`：查询匹配组分配明细。
- `POST /api/v1/matching/results/{id}/confirm`：人工确认待确认匹配，并更新流水和应收金额。
- `POST /api/v1/matching/results/{id}/reject`：人工拒绝待确认匹配，可提交 `reason`。
- `GET /api/v1/matching/exceptions`：查询异常事项。
- `POST /api/v1/matching/exceptions/{id}/assign`：分派异常事项，默认分派给当前用户。
- `POST /api/v1/matching/exceptions/{id}/comment`：追加异常备注。
- `POST /api/v1/matching/exceptions/{id}/resolve`：标记异常已处理。
- `POST /api/v1/matching/exceptions/{id}/close`：关闭已处理异常。
- `POST /api/v1/matching/exceptions/{id}/false-positive`：标记误报，必须提交说明并写入处理/审计日志。
- `GET /api/v1/matching/exceptions/{id}/logs`：查询异常操作日志。
- `POST /api/v1/matching/exceptions/{id}/attachments`：上传异常附件，单文件不超过 10MB。
- `GET /api/v1/matching/exceptions/{id}/attachments`：查询异常附件列表。
- `GET /api/v1/matching/exceptions/attachments/{attachmentId}/download`：下载异常附件。
- `GET /api/v1/projects`：查询项目资金汇总和风险列表。
- `GET /api/v1/projects/{id}`：查询项目详情、合同、应收、关联流水和异常。
- `GET /api/v1/bank-transactions`：按账户、合同编号、项目编号、交易日期和匹配状态分页查询银行流水。
- `GET /api/v1/bank-transactions/{id}`：查看流水、匹配结果和审计日志。
- `POST /api/v1/bank-transactions/{id}/manual-classify`：人工更新流水分类和用途，并写入审计日志。
- `POST /api/v1/bank-transactions/{id}/unlink`：解除流水与匹配组的关联，回滚应收节点已收金额并保留软删除记录。
- `GET /api/v1/bank-transactions/export`：按账户、日期、匹配状态和分类筛选，导出 UTF-8 BOM CSV；Excel 导出暂未实现。
- `GET /api/v1/contracts/{id}`：查看合同、应收节点、关联流水和审计日志。
- `GET /api/v1/matching/results/{id}`：查看匹配结果、原始流水、关联异常和审计日志。
- `GET /api/v1/matching/exceptions/{id}`：查看异常来源和处理日志。
- `GET /api/v1/audit-logs`：按 `target_type`、`target_id`、`action` 分页查询审计日志。

合同、应收计划、匹配结果和异常列表支持分页及业务筛选，返回结构统一为：

```json
{"items": [], "page": 1, "page_size": 20, "total": 0}
```

详情接口用于财务对账追溯，所有查询按当前登录租户隔离；匹配结果保留确认人和确认时间，审计日志保留操作人、TraceID、对象和操作详情。驾驶舱的应收总额、已收金额、逾期未收金额和异常数量均从数据库实时汇总。

当前匹配支持精确匹配、部分收款、拆分匹配、合并匹配、未知收款和逾期未收；客户名称比较会统一处理公司后缀、空格和标点，且可通过 `MATCH_CUSTOMER_NAME_MIN_LENGTH` 和 `MATCH_CUSTOMER_NAME_ALLOW_CONTAINS` 配置匹配规则。部分收款候选需人工确认或拒绝，异常事项支持分派、备注、处理、关闭和日志追踪。

项目风险为 MVP 规则评分：基础分 100；存在逾期应收扣 30 分，回款率低于 50% 扣 30 分、低于 80% 扣 15 分，每个未关闭/未误报异常扣 10 分，最多扣 30 分。分数 `>=80` 为 healthy，`60-79` 为 warning，低于 60 为 danger。项目汇总中的合同金额、应收金额和已收金额采用独立聚合，避免多个应收节点造成合同金额重复计算。

异常附件当前存储在本地 H2 BLOB，用于开发和 MVP 验证；生产环境需迁移到对象存储，并补充删除、预览、病毒扫描和权限细化能力。

匹配计账统一通过 `match_result_allocation` 分配明细完成：普通匹配是一条流水到一个应收节点，拆分匹配是一条流水分配到多个应收节点，合并匹配是多条流水分配到一个应收节点。`match_result` 记录 `match_group_id`、`allocation_mode` 和单条 `allocated_amount`；确认或拒绝任一待确认结果时按 `match_group_id` 整组处理。自动匹配成功后立即按分配明细更新流水匹配状态和应收已收金额，金额超过应收剩余金额时拒绝计账。

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
