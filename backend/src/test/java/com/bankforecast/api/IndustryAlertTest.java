package com.bankforecast.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** P3-8 行业预警规则五项（PRD §8.3）。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class IndustryAlertTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void industryAlertsFiveRules() throws Exception {
    String token = loginToken("finance01");
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    String marker = UUID.randomUUID().toString().substring(0, 8);

    // 规则1：验收 40 天前 + 验收款未收
    seedContractPlan(tenantId, "AL1-" + marker, "客户" + marker, null, LocalDate.now().minusDays(40),
        "验收款", "acceptance", LocalDate.now().minusDays(10));
    // 规则2：验收 100 天前 + 质保金未收
    seedContractPlan(tenantId, "AL2-" + marker, "客户" + marker, null, LocalDate.now().minusDays(100),
        "质保金", "receivable", LocalDate.now().plusDays(30));
    // 规则3：同客户两个项目均逾期未收
    seedContractPlan(tenantId, "AL3A-" + marker, "连逾客户" + marker, "P3A-" + marker, null,
        "一期款", "receivable", LocalDate.now().minusDays(5));
    seedContractPlan(tenantId, "AL3B-" + marker, "连逾客户" + marker, "P3B-" + marker, null,
        "二期款", "receivable", LocalDate.now().minusDays(5));
    // 规则4：巨额工资类支出使可用资金不足两月所需
    seedPayrollExpense(tenantId, marker);
    // 规则5：时间进度过半但零收款的项目
    Long projectId = seedProject(tenantId, "AL5-" + marker, LocalDate.now().minusDays(10),
        LocalDate.now().plusDays(10), null);
    jdbcTemplate.update(
        "insert into contract (tenant_id, contract_no, contract_name, customer_name, project_id, project_no, project_name, contract_amount, status) values (?, ?, ?, ?, ?, ?, ?, 5000.00, 'active')",
        tenantId, "AL5-CT-" + marker, "合同AL5", "客户" + marker, projectId, "AL5-" + marker, "项目AL5");
    Long contractId5 = jdbcTemplate.queryForObject(
        "select id from contract where tenant_id = ? and contract_no = ?", Long.class, tenantId, "AL5-CT-" + marker);
    jdbcTemplate.update(
        "insert into contract_receivable_plan (tenant_id, contract_id, project_id, node_name, node_type, due_date, plan_amount, paid_amount, status) values (?, ?, ?, '首付款', 'receivable', ?, 5000.00, 0, 'unpaid')",
        tenantId, contractId5, projectId, Date.valueOf(LocalDate.now().plusDays(20)));

    mockMvc.perform(post("/api/v1/rules/industry-alerts/run")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.acceptance_overdue").value(1))
        .andExpect(jsonPath("$.data.warranty_overdue").value(1))
        .andExpect(jsonPath("$.data.customer_risk").value(1))
        .andExpect(jsonPath("$.data.payroll_gap").value(1))
        .andExpect(jsonPath("$.data.project_cashflow_risk").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

    // 幂等：再跑一次不重复生成
    mockMvc.perform(post("/api/v1/rules/industry-alerts/run")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.acceptance_overdue").value(0))
        .andExpect(jsonPath("$.data.customer_risk").value(0));

    assertTrue(countException(tenantId, "acceptance_overdue") >= 1);
    assertTrue(countException(tenantId, "warranty_overdue") >= 1);
    assertTrue(countException(tenantId, "customer_risk") >= 1);
    assertTrue(countException(tenantId, "payroll_gap") >= 1);
    assertTrue(countException(tenantId, "project_cashflow_risk") >= 1);
    assertTrue(jdbcTemplate.queryForObject(
        "select count(*) from exception_case where tenant_id = ? and exception_type = 'project_cashflow_risk' and source_id = ?",
        Integer.class, tenantId, projectId) > 0);
  }

  @Test
  void industryAlertsRequirePermission() throws Exception {
    String token = loginToken("biz01");
    mockMvc.perform(post("/api/v1/rules/industry-alerts/run")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  private void seedContractPlan(Long tenantId, String contractNo, String customerName, String projectNo,
      LocalDate acceptanceDate, String nodeName, String nodeType, LocalDate dueDate) {
    Long projectId = null;
    if (projectNo != null || acceptanceDate != null) {
      projectId = seedProject(tenantId, projectNo == null ? "PJ-" + contractNo : projectNo,
          acceptanceDate == null ? null : acceptanceDate.minusDays(60),
          acceptanceDate == null ? null : acceptanceDate.plusDays(60), acceptanceDate);
    }
    jdbcTemplate.update(
        "insert into contract (tenant_id, contract_no, contract_name, customer_name, project_id, project_no, project_name, contract_amount, status) values (?, ?, ?, ?, ?, ?, ?, 10000.00, 'active')",
        tenantId, contractNo, "合同" + contractNo, customerName, projectId, projectNo,
        projectNo == null ? null : "项目" + projectNo);
    Long contractId = jdbcTemplate.queryForObject(
        "select id from contract where tenant_id = ? and contract_no = ?", Long.class, tenantId, contractNo);
    jdbcTemplate.update(
        "insert into contract_receivable_plan (tenant_id, contract_id, project_id, node_name, node_type, due_date, plan_amount, paid_amount, status) values (?, ?, ?, ?, ?, ?, 5000.00, 0, 'unpaid')",
        tenantId, contractId, projectId, nodeName, nodeType, Date.valueOf(dueDate));
  }

  private Long seedProject(Long tenantId, String projectNo, LocalDate start, LocalDate delivery,
      LocalDate acceptance) {
    jdbcTemplate.update(
        "insert into project (tenant_id, project_no, project_name, customer_name, project_status, start_date, delivery_date, acceptance_date) values (?, ?, ?, ?, 'active', ?, ?, ?)",
        tenantId, projectNo, "项目" + projectNo, "客户", start == null ? null : Date.valueOf(start),
        delivery == null ? null : Date.valueOf(delivery),
        acceptance == null ? null : Date.valueOf(acceptance));
    return jdbcTemplate.queryForObject(
        "select id from project where tenant_id = ? and project_no = ?", Long.class, tenantId, projectNo);
  }

  private void seedPayrollExpense(Long tenantId, String marker) {
    Long accountId = jdbcTemplate.queryForObject(
        "select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    jdbcTemplate.update(
        "insert into import_job (tenant_id, job_type, source_type, file_name, status) values (?, 'bank_statement', 'file', ?, 'success')",
        tenantId, "alert-seed-" + marker + ".csv");
    Long jobId = jdbcTemplate.queryForObject(
        "select id from import_job where tenant_id = ? and file_name = ?", Long.class, tenantId,
        "alert-seed-" + marker + ".csv");
    jdbcTemplate.update(
        "insert into bank_transaction (tenant_id, bank_account_id, import_job_id, transaction_no, transaction_date, direction, amount, match_status) values (?, ?, ?, ?, ?, 'expense', 999999999.00, 'unmatched')",
        tenantId, accountId, jobId, "PAYROLL-" + marker, Date.valueOf(LocalDate.now().minusDays(5)));
    jdbcTemplate.update(
        "update bank_transaction set summary = '发放工资' where transaction_no = ?",
        "PAYROLL-" + marker);
  }

  private int countException(Long tenantId, String type) {
    return jdbcTemplate.queryForObject(
        "select count(*) from exception_case where tenant_id = ? and exception_type = ? and deleted_at is null",
        Integer.class, tenantId, type);
  }

  private String loginToken(String loginName) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"" + loginName + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
