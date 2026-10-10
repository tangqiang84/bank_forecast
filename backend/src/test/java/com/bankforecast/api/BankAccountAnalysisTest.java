package com.bankforecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 账户分析视图（活跃度排名/余额趋势/用途分布/销户建议）和 use_type 字段。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class BankAccountAnalysisTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void createAccountWithUseTypeAndRejectInvalid() throws Exception {
    String token = loginToken("finance01");
    String marker = UUID.randomUUID().toString().substring(0, 8);

    mockMvc.perform(post("/api/v1/bank-accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bankCode\":\"TEST\",\"bankName\":\"测试银行\",\"accountName\":\"分析户"
                + marker + "\",\"accountNo\":\"999900001111" + marker.substring(0, 4)
                + "\",\"currency\":\"CNY\",\"currentBalance\":5000.00,\"useType\":\"operation\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.use_type").value("operation"));

    mockMvc.perform(post("/api/v1/bank-accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bankCode\":\"TEST\",\"bankName\":\"测试银行\",\"accountName\":\"非法用途户\",\"accountNo\":\"999900002222\",\"currency\":\"CNY\",\"useType\":\"vault\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  @SuppressWarnings("unchecked")
  void analysisBlocksAndInvariants() throws Exception {
    String token = loginToken("finance01");
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    String marker = UUID.randomUUID().toString().substring(0, 8);

    // 低余额无动账账户（应触发销户建议）和工资户（不应触发）
    Long idleAccountId = createAccount(token, "闲置分析户" + marker, "5000.00", null);
    Long payrollAccountId = createAccount(token, "工资分析户" + marker, "3000.00", "payroll");
    Long activeAccountId = createAccount(token, "活跃分析户" + marker, "80000.00", "operation");
    seedTransactions(tenantId, activeAccountId, marker, 3);

    MvcResult result = mockMvc.perform(get("/api/v1/bank-accounts/analysis?days=30")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.activity_window_days").value(90))
        .andExpect(jsonPath("$.data.activity_ranking").isArray())
        .andExpect(jsonPath("$.data.balance_trend.accounts").isArray())
        .andExpect(jsonPath("$.data.use_type_distribution").isArray())
        .andExpect(jsonPath("$.data.close_suggestions").isArray())
        .andReturn();

    Map<String, Object> data = (Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper()
        .readValue(result.getResponse().getContentAsString(), Map.class).get("data");

    // 活跃度排名：种子活跃账户排第一（3 笔 > 其他 0 笔窗口内账户可能并存，至少出现在前列且笔数为 3）
    List<Map<String, Object>> ranking = (List<Map<String, Object>>) data.get("activity_ranking");
    Map<String, Object> activeRow = ranking.stream()
        .filter(row -> ((Number) row.get("account_id")).longValue() == activeAccountId).findFirst().orElse(null);
    assertTrue(activeRow != null && ((Number) activeRow.get("txn_count_90d")).intValue() == 3);

    // 余额趋势不变量：每账户末点余额 = 当前余额
    Map<String, Object> trend = (Map<String, Object>) data.get("balance_trend");
    for (Map<String, Object> line : (List<Map<String, Object>>) trend.get("accounts")) {
      List<Map<String, Object>> points = (List<Map<String, Object>>) line.get("points");
      BigDecimal lastBalance = new BigDecimal(String.valueOf(points.get(points.size() - 1).get("balance")));
      BigDecimal current = jdbcTemplate.queryForObject(
          "select current_balance from bank_account where id = ?", BigDecimal.class,
          ((Number) line.get("account_id")).longValue());
      assertEquals(0, current.compareTo(lastBalance));
    }

    // 销户建议：闲置低余额户命中，工资户不命中
    List<Map<String, Object>> suggestions = (List<Map<String, Object>>) data.get("close_suggestions");
    assertTrue(suggestions.stream().anyMatch(
        row -> ((Number) row.get("account_id")).longValue() == idleAccountId));
    assertTrue(suggestions.stream().noneMatch(
        row -> ((Number) row.get("account_id")).longValue() == payrollAccountId));

    // 用途分布包含 operation 和 payroll
    List<Map<String, Object>> distribution = (List<Map<String, Object>>) data.get("use_type_distribution");
    assertTrue(distribution.stream().anyMatch(row -> "operation".equals(row.get("use_type"))));
    assertTrue(distribution.stream().anyMatch(row -> "payroll".equals(row.get("use_type"))));
  }

  @Test
  void proposeCloseAndCancelCloseFlow() throws Exception {
    String token = loginToken("finance01");
    Long accountId = createAccount(token, "销户流转户" + UUID.randomUUID().toString().substring(0, 8), "2000.00", null);

    mockMvc.perform(post("/api/v1/bank-accounts/" + accountId + "/propose-close")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"长期闲置低余额\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("pending_close"));

    // 重复发起返回 44015
    mockMvc.perform(post("/api/v1/bank-accounts/" + accountId + "/propose-close")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"重复\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(44015));

    // 撤销待销户回 active
    mockMvc.perform(post("/api/v1/bank-accounts/" + accountId + "/cancel-close")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("active"));

    // 原因为空返回 40001
    mockMvc.perform(post("/api/v1/bank-accounts/" + accountId + "/propose-close")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  void analysisValidationAndPermission() throws Exception {
    String token = loginToken("finance01");
    mockMvc.perform(get("/api/v1/bank-accounts/analysis?days=91")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));

    String bizToken = loginToken("biz01");
    mockMvc.perform(get("/api/v1/bank-accounts/analysis")
            .header("Authorization", "Bearer " + bizToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  private Long createAccount(String token, String name, String balance, String useType) throws Exception {
    String useTypeField = useType == null ? "" : ",\"useType\":\"" + useType + "\"";
    MvcResult result = mockMvc.perform(post("/api/v1/bank-accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bankCode\":\"TEST\",\"bankName\":\"测试银行\",\"accountName\":\"" + name
                + "\",\"accountNo\":\"9999" + String.valueOf(System.nanoTime()).substring(7)
                + "\",\"currency\":\"CNY\",\"currentBalance\":" + balance + useTypeField + "}"))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    return Long.valueOf(body.substring(body.indexOf("\"id\":") + 5, body.indexOf(',', body.indexOf("\"id\":"))));
  }

  private void seedTransactions(Long tenantId, Long accountId, String marker, int count) {
    jdbcTemplate.update(
        "insert into import_job (tenant_id, job_type, source_type, file_name, status) values (?, 'bank_statement', 'file', ?, 'success')",
        tenantId, "analysis-seed-" + marker + ".csv");
    Long jobId = jdbcTemplate.queryForObject(
        "select id from import_job where tenant_id = ? and file_name = ?", Long.class, tenantId,
        "analysis-seed-" + marker + ".csv");
    for (int i = 0; i < count; i++) {
      jdbcTemplate.update(
          "insert into bank_transaction (tenant_id, bank_account_id, import_job_id, transaction_no, transaction_date, direction, amount, match_status) values (?, ?, ?, ?, ?, 'income', ?, 'unmatched')",
          tenantId, accountId, jobId, "ANA-SEED-" + marker + "-" + i,
          java.sql.Date.valueOf(java.time.LocalDate.now().minusDays(i + 1)),
          new BigDecimal("1000.00"));
    }
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
