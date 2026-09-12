# backend AGENTS.md — Java 后端开发规则

## 1. 范围与技术基线

适用于 `bank_forecast/backend`。当前模块为 Java Spring Boot 后端：JDK 8、Spring Boot 2.7.18、Spring JDBC、Flyway、H2、Maven Wrapper。以 `pom.xml` 为最终版本依据。

源码目录：`src/main/java`、`src/main/resources`；测试目录：`src/test/java`。

## 2. 工作原则

- 需求不清时先集中澄清；清晰时给出计划并开工。
- 按“设计 → 开发 → 验证 → 交付”小步实施，禁止无关重构。
- 长任务分阶段汇报，真实报告命令和结果。
- 禁止用 mock 结果冒充真实接口或业务完成状态。

## 3. 常用命令

```bash
./mvnw clean compile
./mvnw test
./mvnw verify
./mvnw spring-boot:run
```

统一使用 Maven Wrapper，不依赖全局 Maven。必须使用完整 JDK，不使用 JRE。代码必须兼容 Java 8，禁止使用 Java 9+ API。

当前 `pom.xml` 未配置的 Checkstyle、SpotBugs、Spotless、JaCoCo、Testcontainers 等工具，不得作为已完成的强制检查；接入时必须同时提交配置、依赖、文档和测试。

## 4. 分层与代码规范

建议按以下职责组织：`api/controller`、`dto`、`service`、`repository`、`security`、`integration`、`audit`、`config`、`exception`。

- Controller 负责 HTTP 参数、校验和响应，不直接访问数据库。
- Service 负责业务规则、事务边界和幂等性。
- Repository 只负责数据访问，不处理 HTTP 异常。
- 对外使用 DTO，不直接暴露数据库实体。
- 日志使用 SLF4J；禁止 `System.out.println`、`printStackTrace`、空 catch 和吞掉 `InterruptedException`。
- 金额使用 `BigDecimal` 和数据库定点数，明确币种、小数位和舍入规则。

## 5. API、错误与安全

所有接口必须使用 DTO、Bean Validation 和统一错误结构：

```json
{"error_code":"BUSINESS_ERROR","message":"用户可理解的错误","trace_id":"trace-id"}
```

不得返回堆栈、数据库错误或敏感内部信息。所有业务查询必须带租户条件并校验资源归属。登录失败不得泄露账号存在性；Token、密码、完整银行账号和隐私不得写日志。

导入、规则修改、匹配确认、异常关闭、报表生成等操作必须审计。

## 6. 数据库和 Flyway

- 结构变更只能新增迁移文件，已执行迁移禁止修改。
- 非空字段必须提供默认值或分阶段迁移方案。
- 数据转换必须有兼容策略、校验和恢复方案。
- 迁移后必须验证旧数据、重复执行和失败恢复。
- 数据库字段变更必须同步实体、DTO、查询、测试和文档。

## 7. 任务和 analytics 调用

导入、预测、报表等长任务必须有任务 ID、状态、失败原因、重试次数和幂等约束。调用 analytics 必须配置地址、连接/读取超时和有限重试，区分超时、网络失败、输入错误和算法错误。analytics 失败必须落库为失败任务，并向前端返回可理解的错误码；禁止无限重试或直接透传 Python 异常。

## 8. 测试与交付

至少覆盖 Controller 校验、Service 规则、Repository 查询、租户权限、统一异常、导入幂等、任务状态、数据库迁移和 analytics 成功/失败调用。

交付前必须实际执行：

- [ ] `./mvnw test`
- [ ] `./mvnw verify`
- [ ] 涉及接口时完成真实 HTTP 验证
- [ ] 涉及数据库时完成 Flyway 和数据验证
- [ ] API 文档已同步
- [ ] 无敏感日志、调试代码和未处理异常
- [ ] 已检查 Git diff

## 9. 配置与依赖

配置使用 `application*.yml` 和环境变量；密钥不得写入源码。新增 Maven 依赖必须说明理由、版本和安全影响，并提交 `pom.xml` 变更。依赖扫描工具未配置时如实记录，不得虚报通过。
