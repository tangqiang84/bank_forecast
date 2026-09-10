package com.bankforecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "bank-forecast.bootstrap.default-admin-password=test-password-123",
    "spring.datasource.url=jdbc:h2:mem:finance_reconciliation_controller_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class FinanceReconciliationControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void importsFinanceRecordsAndIdentifiesBothReconciliationDifferences() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String matchedRecord = "FR-MATCH-" + suffix;
    String unmatchedRecord = "FR-UNMATCHED-" + suffix;
    String transactionNo = "TX-RECON-" + suffix;
    String bankCsv = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + transactionNo + ",2026-09-09,income,100.00,对账客户,已到账\n"
        + "TX-BANK-ONLY-" + suffix + ",2026-09-09,income,200.00,银行独有,待核对\n";
    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "bank.csv", "text/csv", bankCsv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.success_rows").value(2));

    String financeCsv = "record_no,record_type,record_date,posting_date,counterparty_name,amount,summary,source_system\n"
        + matchedRecord + ",receipt,2026-09-09,2026-09-09,对账客户,100.00,已记账,测试财务系统\n"
        + unmatchedRecord + ",payment,2026-09-09,2026-09-09,财务独有,300.00,待核对,测试财务系统\n";
    mockMvc.perform(multipart("/api/v1/imports/finance-records")
            .file(new MockMultipartFile("file", "finance.csv", "text/csv", financeCsv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.success_rows").value(2));

    MvcResult result = mockMvc.perform(post("/api/v1/reconciliation/run?date_from=2026-09-01&date_to=2026-09-30")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.matched").value(1))
        .andExpect(jsonPath("$.data.bank_unrecorded").value(1))
        .andExpect(jsonPath("$.data.finance_unmatched").value(1))
        .andReturn();
    String body = result.getResponse().getContentAsString();
    int start = body.indexOf("job_id") + 8;
    Long jobId = Long.valueOf(body.substring(start, body.indexOf(',', start)));

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
            "/api/v1/reconciliation/results?job_id=" + jobId)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2))
        .andExpect(jsonPath("$.data.items[0].exception_type").exists());

    assertEquals(1, jdbcTemplate.queryForObject("select count(*) from match_result where match_job_id = ? and finance_record_id is not null", Integer.class, jobId).intValue());
    assertEquals(1, jdbcTemplate.queryForObject("select count(*) from exception_case where reconciliation_job_id = ? and exception_type = 'bank_unrecorded'", Integer.class, jobId).intValue());
    assertEquals(1, jdbcTemplate.queryForObject("select count(*) from exception_case where reconciliation_job_id = ? and exception_type = 'finance_unmatched'", Integer.class, jobId).intValue());
  }

  private Long tenantId() { return jdbcTemplate.queryForObject("select tenant_id from user_account where login_name = ?", Long.class, "finance01"); }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"test-password-123\"}"))
        .andExpect(status().isOk()).andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
