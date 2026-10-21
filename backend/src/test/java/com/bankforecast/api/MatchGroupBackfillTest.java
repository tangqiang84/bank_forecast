package com.bankforecast.api;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;

/**
 * P4-16 存量迁移（V30）：历史 suggested 匹配组配对回填 match_group_id。
 * 通过直接执行迁移脚本验证回填、孤儿补建与幂等。
 */
@SpringBootTest(properties = {
    "bank-forecast.bootstrap.default-admin-password=test-password-123",
    "spring.datasource.url=jdbc:mysql://127.0.0.1:${TEST_DB_PORT:3307}/bank_forecast_test_backfill?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class MatchGroupBackfillTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void backfillsCarrierLinkCreatesReviewForOrphansAndIsIdempotent() throws Exception {
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    // 存量数据：合同 + 应收节点 + 流水 + 两个 suggested 匹配组（同计划 G1、G2）+ 历史 partial_receipt 工单（无 match_group_id）
    jdbcTemplate.update("insert into match_job (tenant_id, job_type, scope_type, status, started_at) values (?, 'receivable', 'all', 'success', now())", tenantId);
    Long jobId = jdbcTemplate.queryForObject("select max(id) from match_job where tenant_id = ?", Long.class, tenantId);
    jdbcTemplate.update("insert into contract (tenant_id, contract_no, contract_name, customer_name, contract_amount, status) values (?, ?, ?, '回迁客户', 2000.00, 'active')",
        tenantId, "CT-BF-" + suffix, "回迁合同");
    Long contractId = jdbcTemplate.queryForObject("select id from contract where contract_no = ?", Long.class, "CT-BF-" + suffix);
    jdbcTemplate.update("insert into contract_receivable_plan (tenant_id, contract_id, node_name, node_type, due_date, plan_amount, paid_amount, status) values (?, ?, '首付款', 'milestone', '2026-01-01', 2000.00, 0, 'unpaid')",
        tenantId, contractId);
    Long planId = jdbcTemplate.queryForObject("select id from contract_receivable_plan where contract_id = ?", Long.class, contractId);
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    jdbcTemplate.update("insert into import_job (tenant_id, job_type, source_type, file_name, status) values (?, 'bank_statement', 'file', 'backfill.csv', 'success')", tenantId);
    Long importJobId = jdbcTemplate.queryForObject("select max(id) from import_job where tenant_id = ?", Long.class, tenantId);
    jdbcTemplate.update("insert into bank_transaction (tenant_id, bank_account_id, import_job_id, transaction_no, transaction_date, direction, amount, counterparty_name, summary, match_status) values (?, ?, ?, ?, '2026-01-01', 'income', 500.00, '回迁客户', '首付款', 'unmatched')",
        tenantId, accountId, importJobId, "TX-BF-" + suffix);
    Long txnId = jdbcTemplate.queryForObject("select id from bank_transaction where transaction_no = ?", Long.class, "TX-BF-" + suffix);
    String group1 = "MG-BF1-" + suffix;
    String group2 = "MG-BF2-" + suffix;
    for (String group : new String[] {group1, group2}) {
      jdbcTemplate.update(
          "insert into match_result (tenant_id, match_job_id, match_group_id, allocation_mode, allocated_amount, "
              + "bank_transaction_id, contract_id, contract_receivable_plan_id, match_type, confidence_level, match_status, match_reason) "
              + "values (?, ?, ?, 'single', 500.00, ?, ?, ?, 'partial', 'medium', 'suggested', '存量建议')",
          tenantId, jobId, group, txnId, contractId, planId);
    }
    jdbcTemplate.update(
        "insert into exception_case (tenant_id, exception_no, exception_type, source_type, source_id, title, description, status, severity, due_date) "
            + "values (?, ?, 'partial_receipt', 'contract_receivable_plan', ?, '部分收款：回迁合同', '待人工确认', 'new', 'high', '2026-01-01')",
        tenantId, "EXC-BF-" + suffix, planId);
    Long partialExceptionId = jdbcTemplate.queryForObject(
        "select id from exception_case where exception_no = ?", Long.class, "EXC-BF-" + suffix);

    // 执行 V30 迁移脚本
    javax.sql.DataSource dataSource = jdbcTemplate.getDataSource();
    try (java.sql.Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection,
          new ClassPathResource("db/migration/V30__exception_match_group_backfill.sql"));
    }

    // 回填：partial_receipt 工单承载该计划最新组（G2）
    org.junit.jupiter.api.Assertions.assertEquals(group2, jdbcTemplate.queryForObject(
        "select match_group_id from exception_case where id = ?", String.class, partialExceptionId));
    // 孤儿补建：G1 获得 match_review 工单
    org.junit.jupiter.api.Assertions.assertEquals("match_review", jdbcTemplate.queryForObject(
        "select exception_type from exception_case where tenant_id = ? and match_group_id = ?",
        String.class, tenantId, group1));
    // 无孤儿 suggested 组、无重复承载
    org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
        "select count(*) from (select mr.tenant_id, mr.match_group_id from match_result mr "
            + "where mr.tenant_id = ? and mr.match_status = 'suggested' and mr.deleted_at is null "
            + "and not exists (select 1 from exception_case e where e.tenant_id = mr.tenant_id "
            + "and e.match_group_id = mr.match_group_id and e.deleted_at is null) "
            + "group by mr.tenant_id, mr.match_group_id) t",
        Integer.class, tenantId).intValue());

    // 幂等：再次执行无变化、无唯一键冲突
    try (java.sql.Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection,
          new ClassPathResource("db/migration/V30__exception_match_group_backfill.sql"));
    }
    org.junit.jupiter.api.Assertions.assertEquals(group2, jdbcTemplate.queryForObject(
        "select match_group_id from exception_case where id = ?", String.class, partialExceptionId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
        "select count(*) from exception_case where tenant_id = ? and match_group_id = ? and deleted_at is null",
        Integer.class, tenantId, group1).intValue());
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
        "select count(*) from exception_case where tenant_id = ? and match_group_id = ? and deleted_at is null",
        Integer.class, tenantId, group2).intValue());
  }
}
