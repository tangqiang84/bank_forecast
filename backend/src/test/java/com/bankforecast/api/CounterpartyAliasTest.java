package com.bankforecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** P3-6 对手方别名归一：别名 CRUD 和匹配命中。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class CounterpartyAliasTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void aliasCrudAndMatchingHit() throws Exception {
    String token = loginToken();
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    String marker = UUID.randomUUID().toString().substring(0, 8);
    String canonical = "别名集团" + marker;
    String alias = "别名简称" + marker;

    mockMvc.perform(post("/api/v1/counterparty-aliases")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"alias\":\"" + alias + "\",\"canonical_name\":\"" + canonical + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.alias").value(alias));
    // 重复别名 40001
    mockMvc.perform(post("/api/v1/counterparty-aliases")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"alias\":\"" + alias + "\",\"canonical_name\":\"其他\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));

    // 合同客户为标准名称，流水对方户名为简称 → 别名归一后应精确匹配
    String contractNo = "CT-ALIAS-" + marker;
    String contractCsv = "contract_no,customer_name,project_no,project_name,contract_amount,node_name,node_type,due_date,plan_amount\n"
        + contractNo + "," + canonical + ",PRJ-ALIAS-" + marker + ",别名项目,6000.00,验收款,acceptance,"
        + java.time.LocalDate.now() + ",6000.00\n";
    mockMvc.perform(multipart("/api/v1/imports/contracts")
            .file(new MockMultipartFile("file", "contracts.csv", "text/csv", contractCsv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());

    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    String txCsv = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + "TX-ALIAS-" + marker + "," + java.time.LocalDate.now() + ",income,6000.00," + alias + ",验收款\n";
    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "alias-tx.csv", "text/csv", txCsv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/v1/matching/receivables/run")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());

    assertEquals("matched", jdbcTemplate.queryForObject(
        "select match_status from bank_transaction where tenant_id = ? and transaction_no = ?",
        String.class, tenantId, "TX-ALIAS-" + marker));

    // 删除别名后配置消失
    Long aliasId = jdbcTemplate.queryForObject(
        "select id from counterparty_alias where tenant_id = ? and alias = ?", Long.class, tenantId, alias);
    mockMvc.perform(delete("/api/v1/counterparty-aliases/" + aliasId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deleted").value(true));
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
