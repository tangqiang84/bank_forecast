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

/** 手工新建应付计划：成功、去重、合同关联和参数校验。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class PayableCreateTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void createsPayablePlanManually() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update("insert into contract (tenant_id, contract_no, contract_name, customer_name, contract_amount, status) values (?, ?, ?, ?, 10000.00, 'active')",
        tenantId, "CT-PAY-" + suffix, "应付关联合同", "测试客户");
    Long contractId = jdbcTemplate.queryForObject("select id from contract where tenant_id = ? and contract_no = ?", Long.class, tenantId, "CT-PAY-" + suffix);

    mockMvc.perform(post("/api/v1/payables")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"supplier_name\":\"供应商-" + suffix + "\",\"node_name\":\"到货款\",\"due_date\":\"2026-11-15\",\"plan_amount\":3000.00,\"contract_id\":" + contractId + ",\"remark\":\"手工录入\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.node_name").value("到货款"))
        .andExpect(jsonPath("$.data.status").value("unpaid"))
        .andExpect(jsonPath("$.data.contract_id").value(contractId.intValue()));
    Long planId = jdbcTemplate.queryForObject("select id from contract_payable_plan where tenant_id = ? and contract_id = ? and node_name = ?", Long.class, tenantId, contractId, "到货款");
    Integer audit = jdbcTemplate.queryForObject(
        "select count(*) from audit_log where action = 'CREATE_PAYABLE_PLAN' and target_id = ?", Integer.class, String.valueOf(planId));
    assertTrue(audit != null && audit > 0, "CREATE_PAYABLE_PLAN 审计应已记录");
    mockMvc.perform(get("/api/v1/payables?page=1&page_size=50")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.supplier_name == '供应商-" + suffix + "')]").exists());

    mockMvc.perform(post("/api/v1/payables")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"supplier_name\":\"供应商-" + suffix + "\",\"node_name\":\"到货款\",\"due_date\":\"2026-11-15\",\"plan_amount\":3000.00,\"contract_id\":" + contractId + "}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("相同应付计划已存在"));
    mockMvc.perform(post("/api/v1/payables")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"node_name\":\"缺供应商\",\"due_date\":\"2026-11-15\",\"plan_amount\":100.00}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("supplier_name 不能为空"));
    mockMvc.perform(post("/api/v1/payables")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"supplier_name\":\"供应商-" + suffix + "\",\"node_name\":\"零金额\",\"due_date\":\"2026-11-15\",\"plan_amount\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("plan_amount 必须大于 0"));
    mockMvc.perform(post("/api/v1/payables")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"supplier_name\":\"供应商-" + suffix + "\",\"node_name\":\"合同不存在\",\"due_date\":\"2026-11-15\",\"plan_amount\":100.00,\"contract_id\":999999999}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("关联合同不存在"));
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
