package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** P3-3 异常按类型自动分派：规则配置 + 异常创建时自动分派。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class ExceptionAutoAssignTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void configureAndAutoAssignOnExceptionCreation() throws Exception {
    String token = loginToken("finance01");
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    Long cashierId = jdbcTemplate.queryForObject(
        "select id from user_account where login_name = 'cashier01' and tenant_id = ?", Long.class, tenantId);

    mockMvc.perform(put("/api/v1/rules/exception-assignment")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"assignments\":{\"unknown_receipt\":" + cashierId + "}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.exception_type == 'unknown_receipt')].owner_user_id").exists());

    // 导入一笔无法匹配的到账流水 → 未知收款异常应自动分派给出纳
    String marker = UUID.randomUUID().toString().substring(0, 8);
    String csv = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + "AUTO-ASSIGN-" + marker + ",2026-10-10,income,777.00,陌生对手" + marker + ",来款不明\n";
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "auto-assign.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/v1/matching/receivables/run")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());

    Long ownerId = jdbcTemplate.queryForObject(
        "select owner_user_id from exception_case where tenant_id = ? and exception_type = 'unknown_receipt' "
            + "and source_type = 'bank_transaction' and source_id = "
            + "(select id from bank_transaction where tenant_id = ? and transaction_no = ?)",
        Long.class, tenantId, tenantId, "AUTO-ASSIGN-" + marker);
    org.junit.jupiter.api.Assertions.assertEquals(cashierId, ownerId);
  }

  @Test
  void matchReviewTypeAcceptedInAssignmentConfig() throws Exception {
    String token = loginToken("finance01");
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    Long cashierId = jdbcTemplate.queryForObject(
        "select id from user_account where login_name = 'cashier01' and tenant_id = ?", Long.class, tenantId);

    // match_review（待核销）接入自动分派配置（P4-8）
    mockMvc.perform(put("/api/v1/rules/exception-assignment")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"assignments\":{\"match_review\":" + cashierId + "}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.exception_type == 'match_review')].owner_user_id")
            .value(org.hamcrest.Matchers.contains(cashierId.intValue())));

    // 清理配置
    mockMvc.perform(put("/api/v1/rules/exception-assignment")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"assignments\":{\"match_review\":\"\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.exception_type == 'match_review')].owner_user_id")
            .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
  }

  @Test
  void assignmentValidationAndPermission() throws Exception {
    String token = loginToken("finance01");
    mockMvc.perform(put("/api/v1/rules/exception-assignment")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"assignments\":{\"not_a_type\":1}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
    mockMvc.perform(put("/api/v1/rules/exception-assignment")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"assignments\":{\"fund_gap\":999999}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(42001));

    String bizToken = loginToken("biz01");
    mockMvc.perform(put("/api/v1/rules/exception-assignment")
            .header("Authorization", "Bearer " + bizToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"assignments\":{\"fund_gap\":1}}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
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
