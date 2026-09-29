package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class TraceIdFilterTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void inboundTraceIdEchoesInResponseBodyAndHeader() throws Exception {
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Trace-Id", "trace-e2e-login-001")
            .content("{\"login_name\":\"finance01\",\"password\":\"" + TEST_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", "trace-e2e-login-001"))
        .andExpect(jsonPath("$.trace_id").value("trace-e2e-login-001"));
  }

  @Test
  void invalidInboundTraceIdIsReplaced() throws Exception {
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Trace-Id", "bad trace <script>")
            .content("{\"login_name\":\"finance01\",\"password\":\"" + TEST_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trace_id").isNotEmpty())
        .andExpect(result -> Assertions.assertFalse(
            result.getResponse().getContentAsString().contains("bad trace")));
  }

  @Test
  void auditLogUsesInboundTraceId() throws Exception {
    String token = loginToken();
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    mockMvc.perform(put("/api/v1/rules/config")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId))
            .header("X-Trace-Id", "trace-e2e-audit-002")
            .content("{\"match_scan_window_days\":30,\"match_exact_window_days\":7,\"match_suggest_window_days\":14}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trace_id").value("trace-e2e-audit-002"));
    String auditTrace = jdbcTemplate.queryForObject(
        "select trace_id from audit_log where tenant_id = ? and action = 'UPDATE_RULE_CONFIG' order by id desc limit 1",
        String.class, tenantId);
    Assertions.assertEquals("trace-e2e-audit-002", auditTrace);
  }

  @Test
  void missingTraceIdIsGenerated() throws Exception {
    mockMvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trace_id").isNotEmpty())
        .andExpect(header().exists("X-Trace-Id"));
  }

  private String loginToken() throws Exception {
    org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"" + TEST_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
