package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** P3-4 内部转账/冲正自动识别。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class InternalTransferDetectionTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void detectsInternalTransferAndReversalAndRefund() throws Exception {
    String token = loginToken();
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    Long accountId = jdbcTemplate.queryForObject(
        "select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    String ownAccountName = jdbcTemplate.queryForObject(
        "select account_name from bank_account where id = ?", String.class, accountId);
    String marker = UUID.randomUUID().toString().substring(0, 8);

    String csv = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + "IT-TX-" + marker + "-1,2026-10-10,expense,100.00," + ownAccountName + ",账户间调拨\n"
        + "IT-TX-" + marker + "-2,2026-10-10,income,200.00,客户甲,错款冲正\n"
        + "IT-TX-" + marker + "-3,2026-10-10,income,300.00,客户乙,客户退款退回\n";
    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "internal-transfer.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success_rows").value(3));

    org.junit.jupiter.api.Assertions.assertEquals("transfer", jdbcTemplate.queryForObject(
        "select direction from bank_transaction where tenant_id = ? and transaction_no = ?",
        String.class, tenantId, "IT-TX-" + marker + "-1"));
    org.junit.jupiter.api.Assertions.assertEquals("reversal", jdbcTemplate.queryForObject(
        "select direction from bank_transaction where tenant_id = ? and transaction_no = ?",
        String.class, tenantId, "IT-TX-" + marker + "-2"));
    org.junit.jupiter.api.Assertions.assertEquals("refund", jdbcTemplate.queryForObject(
        "select direction from bank_transaction where tenant_id = ? and transaction_no = ?",
        String.class, tenantId, "IT-TX-" + marker + "-3"));
  }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"" + TEST_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
