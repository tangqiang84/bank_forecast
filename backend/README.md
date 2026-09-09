# bank_forecast backend

Java 主后端。

## 启动

```bash
export DEV_ADMIN_PASSWORD='<本地开发密码>'
export JWT_SECRET='<本地随机长字符串>'
./mvnw spring-boot:run
```

默认地址：`http://localhost:8080`

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

当前匹配支持精确匹配、部分收款、未知收款和逾期未收；客户名称比较会统一处理公司后缀、空格和标点，且可通过 `MATCH_CUSTOMER_NAME_MIN_LENGTH` 和 `MATCH_CUSTOMER_NAME_ALLOW_CONTAINS` 配置匹配规则。部分收款候选需人工确认或拒绝，异常事项支持分派、备注、处理、关闭和日志追踪。拆分匹配和合并匹配仍待新增分配明细表后实现。
