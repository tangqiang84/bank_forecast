package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class RuleCenterControllerTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void updateWritesVersionAndRollbackRestores() throws Exception {
    String token = loginToken("finance01");
    Long tenantId = tenantId("finance01");

    mockMvc.perform(put("/api/v1/rules/risk-rules/overdue_ratio_high")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"threshold\":\"0.6\",\"penalty\":\"25\",\"enabled\":true}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.threshold").value(0.6));

    mockMvc.perform(get("/api/v1/rules/risk-rules/overdue_ratio_high/versions")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].version_no").value(2))
        .andExpect(jsonPath("$.data[0].change_source").value("manual"))
        .andExpect(jsonPath("$.data[1].change_source").value("baseline"));

    mockMvc.perform(post("/api/v1/rules/risk-rules/overdue_ratio_high/rollback")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version_no\":1}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.threshold").value(0.5));

    mockMvc.perform(get("/api/v1/rules/risk-rules/overdue_ratio_high/versions")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(3))
        .andExpect(jsonPath("$.data[0].change_source").value("rollback"));
  }

  @Test
  void matchWindowConfigRoundtripAndValidation() throws Exception {
    String token = loginToken("finance01");
    Long tenantId = tenantId("finance01");

    mockMvc.perform(get("/api/v1/rules/config")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.match_scan_window_days").value(30))
        .andExpect(jsonPath("$.data.match_exact_window_days").value(7))
        .andExpect(jsonPath("$.data.match_suggest_window_days").value(14))
        .andExpect(jsonPath("$.data.industry_template").value("it_software"));

    mockMvc.perform(put("/api/v1/rules/config")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"match_scan_window_days\":20,\"match_exact_window_days\":5,\"match_suggest_window_days\":10}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.match_scan_window_days").value(20))
        .andExpect(jsonPath("$.data.match_suggest_window_days").value(10));

    mockMvc.perform(put("/api/v1/rules/config")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"match_scan_window_days\":3,\"match_exact_window_days\":10}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isBadRequest());

    // 恢复默认，避免影响其他用例的匹配行为
    mockMvc.perform(put("/api/v1/rules/config")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"match_scan_window_days\":30,\"match_exact_window_days\":7,\"match_suggest_window_days\":14}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
  }

  @Test
  void appliesIndustryTemplateAndRecordsVersions() throws Exception {
    String token = loginToken("finance01");
    Long tenantId = tenantId("finance01");

    mockMvc.perform(post("/api/v1/rules/industry-template")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"industry\":\"it_software\"}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.industry_template").value("it_software"));

    org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
        "select count(*) from project_risk_rule where tenant_id = ? and rule_code = 'overdue_ratio_high' and penalty = 20",
        Integer.class, tenantId).intValue());
    org.junit.jupiter.api.Assertions.assertEquals(6, jdbcTemplate.queryForObject(
        "select count(*) from project_risk_rule_version where tenant_id = ? and change_source = 'template'",
        Integer.class, tenantId).intValue());

    mockMvc.perform(post("/api/v1/rules/industry-template")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"industry\":\"unknown_industry\"}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void businessRoleCannotWriteRuleConfig() throws Exception {
    String token = loginToken("biz01");
    Long tenantId = tenantId("biz01");

    mockMvc.perform(put("/api/v1/rules/config")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"match_scan_window_days\":20}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));

    mockMvc.perform(get("/api/v1/rules/config")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
  }

  private Long tenantId(String loginName) {
    return jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = ?", Long.class, loginName);
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
