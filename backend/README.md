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
