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

## 合同应收 CSV 导入

接口：`POST /api/v1/imports/contracts`，使用 multipart 字段 `file`。

必填字段：

```csv
contract_no,contract_name,customer_name,contract_amount,node_name,node_type,due_date,plan_amount
CT-001,软件实施合同,示例客户,100000.00,验收款,acceptance,2026-10-01,100000.00
```

可选字段：`project_no`、`project_name`、`owner_name`。

## 回款匹配与异常

- `GET /api/v1/contracts`：查询合同。
- `GET /api/v1/contracts/receivables`：查询应收计划。
- `POST /api/v1/matching/receivables/run`：运行回款匹配并生成异常。
- `GET /api/v1/matching/results`：查询匹配结果。
- `GET /api/v1/matching/exceptions`：查询异常事项。

当前匹配为 MVP 规则，支持精确匹配、部分收款、未知收款和逾期未收；拆分匹配、合并匹配及人工确认将在后续阶段补充。
