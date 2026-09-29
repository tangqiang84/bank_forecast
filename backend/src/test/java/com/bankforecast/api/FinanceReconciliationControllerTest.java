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

  @Test
  void reconcilesMultiBankRowsBySummedAmount() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String bankCsv = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + "TX-MULTI-A" + suffix + ",2026-10-10,income,600.00,聚合客户丙,分批付款一\n"
        + "TX-MULTI-B" + suffix + ",2026-10-11,income,400.00,聚合客户丙,分批付款二\n";
    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "bank.csv", "text/csv", bankCsv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    String financeCsv = "record_no,record_type,record_date,counterparty_name,amount,summary,subject\n"
        + "FR-MULTI-" + suffix + ",receipt,2026-10-10,聚合客户丙,1000.00,合计收款,应收账款\n";
    mockMvc.perform(multipart("/api/v1/imports/finance-records")
            .file(new MockMultipartFile("file", "finance.csv", "text/csv", financeCsv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());

    MvcResult result = mockMvc.perform(post("/api/v1/reconciliation/run?date_from=2026-10-01&date_to=2026-10-31")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.matched").value(0))
        .andExpect(jsonPath("$.data.multi_matched").value(1))
        .andExpect(jsonPath("$.data.bank_unrecorded").value(0))
        .andExpect(jsonPath("$.data.finance_unmatched").value(0))
        .andReturn();
    String body = result.getResponse().getContentAsString();
    int start = body.indexOf("job_id") + 8;
    Long jobId = Long.valueOf(body.substring(start, body.indexOf(',', start)));
    assertEquals(2, jdbcTemplate.queryForObject(
        "select count(*) from match_result where match_job_id = ? and allocation_mode = 'multi'", Integer.class, jobId).intValue());
    assertEquals(1, jdbcTemplate.queryForObject(
        "select count(distinct match_group_id) from match_result where match_job_id = ? and allocation_mode = 'multi'", Integer.class, jobId).intValue());
    assertEquals("应收账款", jdbcTemplate.queryForObject(
        "select subject from finance_record where record_no = ?", String.class, "FR-MULTI-" + suffix));
  }

  @Test
  void classifiesCrossMonthTimingDifference() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String bankCsv = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + "TX-TIMING-" + suffix + ",2027-02-05,income,700.00,跨月客户丁,跨月到账\n";
    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "bank.csv", "text/csv", bankCsv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    String financeCsv = "record_no,record_type,record_date,counterparty_name,amount,summary\n"
        + "FR-TIMING-" + suffix + ",receipt,2027-01-25,跨月客户丁,700.00,当月记账\n";
    mockMvc.perform(multipart("/api/v1/imports/finance-records")
            .file(new MockMultipartFile("file", "finance.csv", "text/csv", financeCsv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());

    MvcResult result = mockMvc.perform(post("/api/v1/reconciliation/run?date_from=2027-01-01&date_to=2027-02-28")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.matched").value(0))
        .andExpect(jsonPath("$.data.timing_difference").value(1))
        .andExpect(jsonPath("$.data.bank_unrecorded").value(0))
        .andExpect(jsonPath("$.data.finance_unmatched").value(0))
        .andReturn();
    String body = result.getResponse().getContentAsString();
    int start = body.indexOf("job_id") + 8;
    Long jobId = Long.valueOf(body.substring(start, body.indexOf(',', start)));
    assertEquals(1, jdbcTemplate.queryForObject(
        "select count(*) from exception_case where reconciliation_job_id = ? and exception_type = 'timing_difference' and severity = 'medium'",
        Integer.class, jobId).intValue());

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
            "/api/v1/reconciliation/results?job_id=" + jobId + "&difference_type=timing_difference")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].exception_type").value("timing_difference"));
  }

  @Test
  void filtersFinanceRecordsBySubject() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String financeCsv = "record_no,record_type,record_date,amount,subject\n"
        + "FR-SUB-A" + suffix + ",receipt,2026-11-05,100.00,应收账款\n"
        + "FR-SUB-B" + suffix + ",payment,2026-11-06,200.00,管理费用\n";
    mockMvc.perform(multipart("/api/v1/imports/finance-records")
            .file(new MockMultipartFile("file", "finance.csv", "text/csv", financeCsv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.success_rows").value(2));

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
            "/api/v1/finance-records?page=1&page_size=20&subject=应收账款")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.record_no == 'FR-SUB-A" + suffix + "')]").exists())
        .andExpect(jsonPath("$.data.items[?(@.record_no == 'FR-SUB-B" + suffix + "')]").isEmpty());

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
            "/api/v1/finance-records?page=1&page_size=20&record_type=payment&date_from=2026-11-01&date_to=2026-11-30")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.record_no == 'FR-SUB-B" + suffix + "')]").exists())
        .andExpect(jsonPath("$.data.items[?(@.record_no == 'FR-SUB-A" + suffix + "')]").isEmpty());
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
