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
