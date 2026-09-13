# backend AGENTS.md — Java 后端开发规则

本文件继承仓库根目录 `AGENTS.md`，仅补充 Java Spring Boot 后端专项规则。

## 1. 范围和技术基线

适用于 `bank_forecast/backend`。

当前技术栈：

- JDK 8；
- Spring Boot 2.7.18；
- Spring JDBC；
- Flyway；
- H2；
- Maven Wrapper。

版本以 `pom.xml` 为准。代码必须兼容 Java 8。

常用命令：

```bash
./mvnw clean compile
./mvnw test
./mvnw verify
./mvnw spring-boot:run
```

统一使用 Maven Wrapper。未配置的 Checkstyle、SpotBugs、Spotless、JaCoCo、Testcontainers 等工具，不得描述为已通过。

## 2. 分层规则

- Controller：路由、参数、认证上下文和响应转换。
- DTO：请求和响应模型。
- Service：业务规则、事务、任务状态、幂等和业务编排。
- 各领域 Repository：数据库访问和复用查询。
- Integration：外部服务客户端。
- Security：认证、权限和租户上下文。
- Audit：审计记录。
- Exception：统一异常处理。

规则：

- 新增 Controller 禁止直接依赖 `JdbcTemplate`、SQL、文件存储或外部 HTTP 客户端。
- Controller 不承载业务规则和复杂数据库查询。
- 复杂或复用查询应下沉到 Repository。
- 存量 Controller 按任务范围逐步迁移到 Service 和 Repository。
- 对外接口优先使用 DTO，不直接暴露数据库实体。
- 不为满足分层要求重写无关接口。

## 3. API、金额和日期

- 金额使用 `BigDecimal`，禁止用 `double` 或 `float` 执行业务计算。
- 数据库金额字段使用定点数，并明确精度、舍入方式和币种。
- 日期使用 `yyyy-MM-dd`。
- 时间使用 ISO-8601 或项目约定格式。
- 新接口必须使用明确的 HTTP 方法、资源路径、DTO 和 Bean Validation。
- 接口统一返回结构化响应和错误。
- 字段变化必须同步所有受影响的 frontend service、OpenAPI、接口契约、测试和变更记录。
- 已发布接口不得无通知删除字段或改变字段含义。

统一错误结构：

```json
{
  "code": 40001,
  "message": "参数校验失败",
  "data": {
    "error_code": "INVALID_PARAMETER",
    "field": "horizon"
  },
  "trace_id": "trace-id"
}
```

不得返回堆栈、SQL、服务器路径或敏感数据。

## 4. 认证、租户和权限

业务接口必须校验用户身份、当前租户、资源归属、功能权限和数据范围权限。

规则：

- 业务查询必须带租户条件。
- 详情、下载、预览和导出必须校验租户和资源归属。
- 不得只根据前端传入 ID 返回资源。
- 租户 ID 以认证上下文为准。
- 权限判断必须在 backend 执行。
- 登录失败不得暴露账号是否存在。
- Token、密码和认证信息不得写入日志。
- 导入、规则修改、匹配确认、异常处理、报表生成和预测任务必须记录审计日志。

## 5. 数据库、事务和任务

- 数据库结构变更只能通过新增 Flyway 迁移。
- 已执行迁移禁止修改、删除或重排。
- 数据修复迁移必须说明前置条件、影响范围和校验方式。
- 必须验证旧数据读取、新数据写入、失败恢复和接口兼容性。
- 大表变更必须评估锁表、索引和执行时间。
- 事务边界定义在 Service 层。
- 单次事务不得覆盖长时间文件解析、外部 HTTP 或大批量计算。
- 长任务使用可查询任务状态。
- 批量任务设置上限并支持分批提交。
- 导入、匹配、异常、规则、报表和预测重试必须具备幂等控制。

## 6. analytics 调用

- 地址从 `ANALYTICS_BASE_URL` 读取，不得硬编码。
- 必须设置连接和读取超时。
- 仅连接失败、连接超时和读取超时允许有限重试。
- 默认最多自动重试 2 次并使用退避。
- 用户手工重试必须具备幂等控制。
- 必须传递 `X-Trace-Id`。
- 必须区分成功、超时、连接失败、鉴权失败和业务错误。
- 不得把 Python 堆栈原样返回前端。
- analytics 失败时记录任务失败状态和原因。
- 结果保存算法方法和模型版本。

## 7. 日志、文件和配置安全

- 使用 SLF4J。
- 禁止 `System.out.println`、`printStackTrace` 和空 catch。
- 不得吞掉 `InterruptedException`。
- 异常必须保留根因。
- 日志应包含必要的 `trace_id`、服务名、环境、任务 ID 或业务主键。
- 日志不得包含密码、Token、完整银行账号、原始文件内容或其他敏感数据。
- 上传、预览、下载、导出和删除接口必须限制大小、行数、格式，校验租户和权限，并防止路径穿越。
- 配置通过 `application*.yml` 和环境变量管理。
- `.env`、密钥文件和证书不得提交。
- 生产配置、CORS、Actuator、H2 Console、对象存储和服务间身份校验以发布检查清单为准。

## 8. 后端测试和交付

根据影响范围覆盖成功、失败、权限、租户越权、分页、脱敏、重复提交、状态转换、文件边界、金额精度、事务回滚、审计和 analytics 异常场景。

接口任务至少提供一种可复现的接口验证方式：

- MockMvc 或集成测试；
- curl 或 Playwright APIRequest。

交付前根据影响范围执行：

```bash
./mvnw test
./mvnw verify
```

涉及编译或依赖时执行：

```bash
./mvnw clean compile
```

未执行的检查、限制和剩余风险必须如实说明。
