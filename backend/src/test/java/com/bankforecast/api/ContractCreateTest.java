package com.bankforecast.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** 手工新建合同与应收计划：成功、唯一约束、金额约束和参数校验。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class ContractCreateTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void createsContractAndReceivablePlanManually() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update("insert into project (tenant_id, project_no, project_name, customer_name, project_status) values (?, ?, ?, ?, 'active')",
        tenantId, "PRJ-CT-" + suffix, "合同关联项目", "测试客户");
    Long projectId = jdbcTemplate.queryForObject("select id from project where project_no = ?", Long.class, "PRJ-CT-" + suffix);

    mockMvc.perform(post("/api/v1/contracts")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contract_no\":\"CT-NEW-" + suffix + "\",\"contract_name\":\"手工合同\",\"customer_name\":\"手工客户\",\"contract_amount\":10000.00,\"project_id\":" + projectId + ",\"sign_date\":\"2026-10-01\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.contract_no").value("CT-NEW-" + suffix))
        .andExpect(jsonPath("$.data.project_no").value("PRJ-CT-" + suffix))
        .andExpect(jsonPath("$.data.status").value("active"));
    Long contractId = jdbcTemplate.queryForObject("select id from contract where tenant_id = ? and contract_no = ?", Long.class, tenantId, "CT-NEW-" + suffix);
    Integer contractAudit = jdbcTemplate.queryForObject(
        "select count(*) from audit_log where action = 'CREATE_CONTRACT' and target_id = ?", Integer.class, String.valueOf(contractId));
    assertTrue(contractAudit != null && contractAudit > 0, "CREATE_CONTRACT 审计应已记录");

    mockMvc.perform(post("/api/v1/receivable-plans")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contract_id\":" + contractId + ",\"node_name\":\"首付款-" + suffix + "\",\"node_type\":\"advance\",\"due_date\":\"2026-11-01\",\"plan_amount\":4000.00}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.node_name").value("首付款-" + suffix))
        .andExpect(jsonPath("$.data.status").value("unpaid"));
    Long planId = jdbcTemplate.queryForObject("select id from contract_receivable_plan where tenant_id = ? and contract_id = ? and node_name = ?", Long.class, tenantId, contractId, "首付款-" + suffix);
    Integer planAudit = jdbcTemplate.queryForObject(
        "select count(*) from audit_log where action = 'CREATE_RECEIVABLE_PLAN' and target_id = ?", Integer.class, String.valueOf(planId));
    assertTrue(planAudit != null && planAudit > 0, "CREATE_RECEIVABLE_PLAN 审计应已记录");
    mockMvc.perform(get("/api/v1/contracts/receivables?page=1&page_size=50&contract_no=CT-NEW-" + suffix)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.node_name == '首付款-" + suffix + "')]").exists());

    mockMvc.perform(post("/api/v1/contracts")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contract_no\":\"CT-NEW-" + suffix + "\",\"contract_name\":\"重复编号\",\"customer_name\":\"手工客户\",\"contract_amount\":100.00}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("合同编号已存在"));
    mockMvc.perform(post("/api/v1/contracts")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contract_no\":\"CT-MISS-" + suffix + "\",\"customer_name\":\"手工客户\",\"contract_amount\":100.00}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("contract_name 不能为空"));
    mockMvc.perform(post("/api/v1/contracts")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contract_no\":\"CT-BAD-" + suffix + "\",\"contract_name\":\"非法状态\",\"customer_name\":\"手工客户\",\"contract_amount\":100.00,\"status\":\"unknown\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("合同状态不合法"));
    mockMvc.perform(post("/api/v1/contracts")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contract_no\":\"CT-NP-" + suffix + "\",\"contract_name\":\"项目不存在\",\"customer_name\":\"手工客户\",\"contract_amount\":100.00,\"project_id\":999999999}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("关联项目不存在"));

    mockMvc.perform(post("/api/v1/receivable-plans")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contract_id\":999999999,\"node_name\":\"x\",\"node_type\":\"advance\",\"due_date\":\"2026-11-01\",\"plan_amount\":1.00}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("关联合同不存在"));
    mockMvc.perform(post("/api/v1/receivable-plans")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contract_id\":" + contractId + ",\"node_name\":\"首付款-" + suffix + "\",\"node_type\":\"advance\",\"due_date\":\"2026-11-02\",\"plan_amount\":100.00}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("该合同下节点名称已存在"));
    mockMvc.perform(post("/api/v1/receivable-plans")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contract_id\":" + contractId + ",\"node_name\":\"非法类型-" + suffix + "\",\"node_type\":\"unknown\",\"due_date\":\"2026-11-02\",\"plan_amount\":100.00}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("节点类型不合法"));
    mockMvc.perform(post("/api/v1/receivable-plans")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contract_id\":" + contractId + ",\"node_name\":\"超合同-" + suffix + "\",\"node_type\":\"milestone\",\"due_date\":\"2026-11-02\",\"plan_amount\":20000.00}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("应收计划金额不能超过合同金额"));
    mockMvc.perform(post("/api/v1/receivable-plans")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contract_id\":" + contractId + ",\"node_name\":\"累计超-" + suffix + "\",\"node_type\":\"milestone\",\"due_date\":\"2026-11-02\",\"plan_amount\":7000.00}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("应收计划金额合计不能超过合同金额"));
  }

  private Long tenantId() {
    return jdbcTemplate.queryForObject("select tenant_id from user_account where login_name = ?", Long.class, "finance01");
  }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"" + TEST_PASSWORD + "\"}"))
        .andExpect(status().isOk()).andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
