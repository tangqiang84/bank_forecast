package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class ProjectControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void projectDetailAggregatesContractAmountOnceWhenThereAreMultipleReceivables() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update("insert into project (tenant_id, project_no, project_name, customer_name, project_status) values (?, ?, ?, ?, 'active')",
        tenantId, "PRJ-" + suffix, "测试项目", "测试客户");
    Long projectId = jdbcTemplate.queryForObject("select id from project where project_no = ?", Long.class, "PRJ-" + suffix);
    jdbcTemplate.update("insert into contract (tenant_id, contract_no, contract_name, customer_name, project_id, project_no, project_name, contract_amount, status) values (?, ?, ?, ?, ?, ?, ?, ?, 'active')",
        tenantId, "CON-" + suffix, "测试合同", "测试客户", projectId, "PRJ-" + suffix, "测试项目", 100.00);
    Long contractId = jdbcTemplate.queryForObject("select id from contract where contract_no = ?", Long.class, "CON-" + suffix);
    jdbcTemplate.update("insert into contract_receivable_plan (tenant_id, contract_id, project_id, node_name, node_type, due_date, plan_amount, paid_amount, status) values (?, ?, ?, ?, 'milestone', '2099-01-01', 60.00, 30.00, 'partial')",
        tenantId, contractId, projectId, "首付款-" + suffix);
    jdbcTemplate.update("insert into contract_receivable_plan (tenant_id, contract_id, project_id, node_name, node_type, due_date, plan_amount, paid_amount, status) values (?, ?, ?, ?, 'milestone', '2099-02-01', 40.00, 20.00, 'partial')",
        tenantId, contractId, projectId, "尾款-" + suffix);

    mockMvc.perform(get("/api/v1/projects/" + projectId)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.summary.contract_amount").value(100.0))
        .andExpect(jsonPath("$.data.summary.receivable_amount").value(100.0))
        .andExpect(jsonPath("$.data.summary.paid_amount").value(50.0));
  }

  @Test
  void updatesProjectAndPersistsRiskRule() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update("insert into project (tenant_id, project_no, project_name, customer_name, project_status) values (?, ?, ?, ?, 'active')",
        tenantId, "PRJ-EDIT-" + suffix, "待编辑项目", "待编辑客户");
    Long projectId = jdbcTemplate.queryForObject("select id from project where project_no = ?", Long.class, "PRJ-EDIT-" + suffix);
    mockMvc.perform(put("/api/v1/projects/" + projectId)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"project_name\":\"更新项目\",\"customer_name\":\"更新客户\",\"project_manager\":\"项目经理\",\"project_status\":\"paused\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.project_name").value("更新项目"))
        .andExpect(jsonPath("$.data.project_status").value("paused"));

    mockMvc.perform(put("/api/v1/projects/risk-rules/paid_rate_low")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON).content("{\"threshold\":0.4,\"penalty\":25,\"enabled\":true}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.threshold").value(0.4))
        .andExpect(jsonPath("$.data.penalty").value(25));

    mockMvc.perform(get("/api/v1/projects/risk-rules")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray());
  }

  @Test
  void createsProjectManuallyAndRejectsDuplicateAndInvalid() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    mockMvc.perform(post("/api/v1/projects")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"project_no\":\"PRJ-NEW-" + suffix + "\",\"project_name\":\"手工新建项目\",\"customer_name\":\"手工客户\",\"project_manager\":\"王五\",\"start_date\":\"2026-10-01\",\"remark\":\"手工录入\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.project_no").value("PRJ-NEW-" + suffix))
        .andExpect(jsonPath("$.data.project_name").value("手工新建项目"))
        .andExpect(jsonPath("$.data.project_status").value("active"));
    mockMvc.perform(get("/api/v1/projects?page=1&page_size=50")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.project_no == 'PRJ-NEW-" + suffix + "')]").exists());
    Integer auditCount = jdbcTemplate.queryForObject(
        "select count(*) from audit_log where action = 'CREATE_PROJECT' and detail like ?", Integer.class, "%PRJ-NEW-" + suffix + "%");
    org.junit.jupiter.api.Assertions.assertTrue(auditCount != null && auditCount > 0, "CREATE_PROJECT 审计应已记录");

    mockMvc.perform(post("/api/v1/projects")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"project_no\":\"PRJ-NEW-" + suffix + "\",\"project_name\":\"重复编号项目\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("项目编号已存在"));

    mockMvc.perform(post("/api/v1/projects")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"project_name\":\"缺少编号\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("project_no 不能为空"));
    mockMvc.perform(post("/api/v1/projects")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"project_no\":\"PRJ-BAD-" + suffix + "\",\"project_name\":\"非法状态\",\"project_status\":\"unknown\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("项目状态不合法"));
  }

  @Test
  void filtersByProjectManagerAndDetailShowsPaymentsAndTimeline() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update("insert into project (tenant_id, project_no, project_name, customer_name, project_manager, project_status, start_date) values (?, ?, ?, ?, ?, 'active', '2026-09-01')",
        tenantId, "PRJ-MGR-" + suffix, "负责人项目", "测试客户", "张三");
    jdbcTemplate.update("insert into project (tenant_id, project_no, project_name, customer_name, project_manager, project_status) values (?, ?, ?, ?, ?, 'active')",
        tenantId, "PRJ-OTHER-" + suffix, "其他项目", "测试客户", "李四");
    Long projectId = jdbcTemplate.queryForObject("select id from project where project_no = ?", Long.class, "PRJ-MGR-" + suffix);

    mockMvc.perform(get("/api/v1/projects?page=1&page_size=50&project_manager=张三")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.project_no == 'PRJ-MGR-" + suffix + "')]").exists())
        .andExpect(jsonPath("$.data.items[?(@.project_no == 'PRJ-OTHER-" + suffix + "')]").isEmpty());

    jdbcTemplate.update("insert into contract (tenant_id, contract_no, contract_name, customer_name, project_id, project_no, project_name, contract_amount, status) values (?, ?, ?, ?, ?, ?, ?, 1000.00, 'active')",
        tenantId, "CON-TL-" + suffix, "付款合同", "测试客户", projectId, "PRJ-MGR-" + suffix, "负责人项目");
    Long contractId = jdbcTemplate.queryForObject("select id from contract where contract_no = ?", Long.class, "CON-TL-" + suffix);
    jdbcTemplate.update("insert into contract_receivable_plan (tenant_id, contract_id, project_id, node_name, node_type, due_date, plan_amount, paid_amount, status) values (?, ?, ?, ?, 'milestone', '2026-12-01', 1000.00, 0.00, 'unpaid')",
        tenantId, contractId, projectId, "验收款-" + suffix);
    Long planId = jdbcTemplate.queryForObject("select id from contract_receivable_plan where contract_id = ?", Long.class, contractId);
    jdbcTemplate.update("insert into bank_transaction (tenant_id, bank_account_id, import_job_id, transaction_no, transaction_date, booking_date, direction, amount, match_status) "
        + "select ?, min(id), 0, ?, '2026-09-15', '2026-09-15', 'expense', 200.00, 'matched' from bank_account where tenant_id = ?",
        tenantId, "TX-PAY-" + suffix, tenantId);
    Long transactionId = jdbcTemplate.queryForObject("select id from bank_transaction where transaction_no = ?", Long.class, "TX-PAY-" + suffix);
    jdbcTemplate.update("insert into match_job (tenant_id, job_type, scope_type, status, started_at) values (?, 'receivable', 'all', 'success', current_timestamp)", tenantId);
    Long jobId = jdbcTemplate.queryForObject("select max(id) from match_job where tenant_id = ?", Long.class, tenantId);
    jdbcTemplate.update("insert into match_result (tenant_id, match_job_id, match_group_id, allocation_mode, allocated_amount, bank_transaction_id, contract_id, contract_receivable_plan_id, project_id, match_type, confidence_level, match_status, match_reason) values (?, ?, ?, 'single', 200.00, ?, ?, ?, ?, 'manual', 'high', 'confirmed', '测试付款关联')",
        tenantId, jobId, "MG-" + suffix, transactionId, contractId, planId, projectId);
    Long resultId = jdbcTemplate.queryForObject("select max(id) from match_result where tenant_id = ?", Long.class, tenantId);
    jdbcTemplate.update("insert into match_result_allocation (tenant_id, match_group_id, match_result_id, bank_transaction_id, contract_receivable_plan_id, allocated_amount, status) values (?, ?, ?, ?, ?, 200.00, 'confirmed')",
        tenantId, "MG-" + suffix, resultId, transactionId, planId);

    mockMvc.perform(get("/api/v1/projects/" + projectId)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.summary.paid_out_amount").value(200.0))
        .andExpect(jsonPath("$.data.payments[0].transaction_no").value("TX-PAY-" + suffix))
        .andExpect(jsonPath("$.data.payments[0].allocated_amount").value(200.0))
        .andExpect(jsonPath("$.data.timeline[?(@.type == 'milestone')]").exists())
        .andExpect(jsonPath("$.data.timeline[?(@.type == 'receivable')]").exists())
        .andExpect(jsonPath("$.data.timeline[?(@.type == 'payment')]").exists());
  }

  @Test
  void negativeCashflowRuleTriggersOnlyWhenNetCashflowIsNegative() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update("insert into project (tenant_id, project_no, project_name, customer_name, project_status) values (?, ?, ?, ?, 'active')",
        tenantId, "PRJ-NEG-" + suffix, "负现金流项目", "测试客户");
    jdbcTemplate.update("insert into project (tenant_id, project_no, project_name, customer_name, project_status) values (?, ?, ?, ?, 'active')",
        tenantId, "PRJ-POS-" + suffix, "正现金流项目", "测试客户");
    Long negProjectId = jdbcTemplate.queryForObject("select id from project where project_no = ?", Long.class, "PRJ-NEG-" + suffix);
    Long posProjectId = jdbcTemplate.queryForObject("select id from project where project_no = ?", Long.class, "PRJ-POS-" + suffix);
    insertAllocatedTransaction(tenantId, suffix, "NEG", negProjectId, "expense", "200.00");
    insertAllocatedTransaction(tenantId, suffix, "POS", posProjectId, "income", "300.00");

    mockMvc.perform(get("/api/v1/projects?page=1&page_size=50")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.project_no == 'PRJ-NEG-" + suffix + "')].risk_factors.negative_cashflow.triggered").value(org.hamcrest.Matchers.contains(true)))
        .andExpect(jsonPath("$.data.items[?(@.project_no == 'PRJ-NEG-" + suffix + "')].risk_factors.negative_cashflow.deduction").value(org.hamcrest.Matchers.contains(15.0)))
        .andExpect(jsonPath("$.data.items[?(@.project_no == 'PRJ-NEG-" + suffix + "')].risk_score").value(org.hamcrest.Matchers.contains(85)))
        .andExpect(jsonPath("$.data.items[?(@.project_no == 'PRJ-NEG-" + suffix + "')].risk_items[0]").value("项目关联流水净现金流为负"))
        .andExpect(jsonPath("$.data.items[?(@.project_no == 'PRJ-POS-" + suffix + "')].risk_factors.negative_cashflow.triggered").value(org.hamcrest.Matchers.contains(false)))
        .andExpect(jsonPath("$.data.items[?(@.project_no == 'PRJ-POS-" + suffix + "')].risk_factors.negative_cashflow.deduction").value(org.hamcrest.Matchers.contains(0)))
        .andExpect(jsonPath("$.data.items[?(@.project_no == 'PRJ-POS-" + suffix + "')].risk_score").value(org.hamcrest.Matchers.contains(100)));
  }

  private void insertAllocatedTransaction(Long tenantId, String suffix, String tag, Long projectId, String direction, String amount) {
    jdbcTemplate.update("insert into contract (tenant_id, contract_no, contract_name, customer_name, project_id, project_no, project_name, contract_amount, status) values (?, ?, ?, ?, ?, ?, ?, 1000.00, 'active')",
        tenantId, "CON-CF-" + tag + "-" + suffix, "现金流合同", "测试客户", projectId, "PRJ-" + tag + "-" + suffix, "现金流项目");
    Long contractId = jdbcTemplate.queryForObject("select id from contract where contract_no = ?", Long.class, "CON-CF-" + tag + "-" + suffix);
    jdbcTemplate.update("insert into contract_receivable_plan (tenant_id, contract_id, project_id, node_name, node_type, due_date, plan_amount, paid_amount, status) values (?, ?, ?, ?, 'milestone', '2026-12-01', 1000.00, 1000.00, 'paid')",
        tenantId, contractId, projectId, "验收款-" + tag + "-" + suffix);
    Long planId = jdbcTemplate.queryForObject("select id from contract_receivable_plan where contract_id = ?", Long.class, contractId);
    jdbcTemplate.update("insert into bank_transaction (tenant_id, bank_account_id, import_job_id, transaction_no, transaction_date, booking_date, direction, amount, match_status) "
        + "select ?, min(id), 0, ?, '2026-09-15', '2026-09-15', ?, " + amount + ", 'matched' from bank_account where tenant_id = ?",
        tenantId, "TX-CF-" + tag + "-" + suffix, direction, tenantId);
    Long transactionId = jdbcTemplate.queryForObject("select id from bank_transaction where transaction_no = ?", Long.class, "TX-CF-" + tag + "-" + suffix);
    jdbcTemplate.update("insert into match_job (tenant_id, job_type, scope_type, status, started_at) values (?, 'receivable', 'all', 'success', current_timestamp)", tenantId);
    Long jobId = jdbcTemplate.queryForObject("select max(id) from match_job where tenant_id = ?", Long.class, tenantId);
    jdbcTemplate.update("insert into match_result (tenant_id, match_job_id, match_group_id, allocation_mode, allocated_amount, bank_transaction_id, contract_id, contract_receivable_plan_id, project_id, match_type, confidence_level, match_status, match_reason) values (?, ?, ?, 'single', " + amount + ", ?, ?, ?, ?, 'manual', 'high', 'confirmed', '现金流规则验证')",
        tenantId, jobId, "MG-CF-" + tag + "-" + suffix, transactionId, contractId, planId, projectId);
    Long resultId = jdbcTemplate.queryForObject("select max(id) from match_result where tenant_id = ?", Long.class, tenantId);
    jdbcTemplate.update("insert into match_result_allocation (tenant_id, match_group_id, match_result_id, bank_transaction_id, contract_receivable_plan_id, allocated_amount, status) values (?, ?, ?, ?, ?, " + amount + ", 'confirmed')",
        tenantId, "MG-CF-" + tag + "-" + suffix, resultId, transactionId, planId);
  }

  private Long tenantId() {
    return jdbcTemplate.queryForObject("select tenant_id from user_account where login_name = ?", Long.class, "finance01");
  }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"test-password-123\"}"))
        .andExpect(status().isOk()).andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
