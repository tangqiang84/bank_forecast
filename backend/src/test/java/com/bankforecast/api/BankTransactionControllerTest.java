package com.bankforecast.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BankTransactionControllerTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void classifiesTransactionAndExportsCsv() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    Long accountId = accountId(tenantId);
    String transactionNo = "TX-CLASSIFY-" + suffix();
    importStatement(token, tenantId, accountId,
        transactionNo + ",2026-09-09,income,128.00,ACME客户,项目回款\n");
    Long transactionId = transactionId(tenantId, transactionNo);

    mockMvc.perform(post("/api/v1/bank-transactions/" + transactionId + "/manual-classify")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"category\":\"客户回款\",\"purpose\":\"验收款\",\"remark\":\"人工复核\"}")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.category").value("客户回款"))
        .andExpect(jsonPath("$.data.purpose").value("验收款"));

    assertEquals("客户回款", jdbcTemplate.queryForObject(
        "select category from bank_transaction where id = ?", String.class, transactionId));

    mockMvc.perform(get("/api/v1/bank-transactions/export?category=客户回款")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", containsString("bank-transactions.csv")))
        .andExpect(content().contentTypeCompatibleWith("text/csv"))
        .andExpect(content().string(containsString("transaction_no,transaction_date,direction")))
        .andExpect(content().string(containsString(transactionNo)));
  }

  @Test
  void unlinksMatchedTransactionAndRollsBackReceivable() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    Long accountId = accountId(tenantId);
    String suffix = suffix();
    String contractNo = "CT-UNLINK-" + suffix;
    String transactionNo = "TX-UNLINK-" + suffix;

    importContract(token, tenantId, contractNo);
    importStatement(token, tenantId, accountId,
        transactionNo + ",2026-09-09,income,1000.00,甲方科技,合同 " + contractNo + " 验收款\n");
    mockMvc.perform(post("/api/v1/matching/receivables/run")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.matched").value(1));

    Long transactionId = transactionId(tenantId, transactionNo);
    Long planId = jdbcTemplate.queryForObject(
        "select p.id from contract_receivable_plan p join contract c on c.id = p.contract_id where c.contract_no = ?",
        Long.class, contractNo);
    Long resultId = jdbcTemplate.queryForObject(
        "select id from match_result where tenant_id = ? and bank_transaction_id = ? and deleted_at is null",
        Long.class, tenantId, transactionId);
    assertNotNull(resultId);
    assertEquals(new BigDecimal("1000.00"), jdbcTemplate.queryForObject(
        "select paid_amount from contract_receivable_plan where id = ?", BigDecimal.class, planId));

    mockMvc.perform(post("/api/v1/bank-transactions/" + transactionId + "/unlink")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"人工复核后解除关联\"}")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.match_status").value("unmatched"))
        .andExpect(jsonPath("$.data.unlinked_groups").value(1))
        .andExpect(jsonPath("$.data.rolled_back_plans").value(1));

    assertEquals(new BigDecimal("0.00"), jdbcTemplate.queryForObject(
        "select paid_amount from contract_receivable_plan where id = ?", BigDecimal.class, planId));
    assertEquals("unpaid", jdbcTemplate.queryForObject(
        "select status from contract_receivable_plan where id = ?", String.class, planId));
    assertEquals(1, jdbcTemplate.queryForObject(
        "select count(*) from match_result_allocation where id in (select id from match_result_allocation where bank_transaction_id = ?) and status = 'unlinked' and deleted_at is not null",
        Integer.class, transactionId).intValue());
    assertEquals(1, jdbcTemplate.queryForObject(
        "select count(*) from match_result where id = ? and match_status = 'unlinked' and deleted_at is not null",
        Integer.class, resultId).intValue());
  }

  private void importContract(String token, Long tenantId, String contractNo) throws Exception {
    String csv = "contract_no,contract_name,customer_name,project_no,project_name,contract_amount,node_name,node_type,due_date,plan_amount,owner_name\n"
        + contractNo + ",软件实施合同,甲方科技,PRJ-" + contractNo + ",甲方一期项目,1000.00,验收款,acceptance,2026-09-09,1000.00,财务负责人\n";
    mockMvc.perform(multipart("/api/v1/imports/contracts")
            .file(new MockMultipartFile("file", "contracts.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success_rows").value(1));
  }

  private void importStatement(String token, Long tenantId, Long accountId, String row) throws Exception {
    String csv = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n" + row;
    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success_rows").value(1));
  }

  private Long accountId(Long tenantId) {
    return jdbcTemplate.queryForObject(
        "select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
  }

  private Long transactionId(Long tenantId, String transactionNo) {
    return jdbcTemplate.queryForObject(
        "select id from bank_transaction where tenant_id = ? and transaction_no = ?",
        Long.class, tenantId, transactionNo);
  }

  private Long tenantId() {
    return jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = ?", Long.class, "finance01");
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

  private String suffix() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
