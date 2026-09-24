package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.StreamUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class ImportControllerTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void importsCsvBankStatementsAndListsTransactions() throws Exception {
    String token = loginToken();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject("select tenant_id from bank_account where id = ?", Long.class, accountId);
    String transactionNo = "TXN-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "transaction_no,transaction_date,direction,amount,balance_after,counterparty_name,summary\n"
        + transactionNo + ",2026-09-09,income,128400.00,2865300.00,ACME客户,项目回款\n";

    MockMultipartFile file = new MockMultipartFile(
        "file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(file)
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(1));

    mockMvc.perform(get("/api/v1/bank-transactions")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").isNumber());
  }

  @Test
  void importRequiresLogin() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv", new byte[0]);

    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(file)
            .param("bank_account_id", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(41001));
  }

  @Test
  void importsValidRowsAndReportsInvalidRowsWithRealLineNumbers() throws Exception {
    String token = loginToken();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject("select tenant_id from bank_account where id = ?", Long.class, accountId);
    String validTransactionNo = "TXN-VALID-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "transaction_no,transaction_date,direction,amount,balance_after,counterparty_name,summary\n"
        + "TXN-BAD-" + UUID.randomUUID().toString().replace("-", "") + ",2026-09-09,income,not-number,,,\n"
        + validTransactionNo + ",2026-09-09,income,12.00,,,测试\n";

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "mixed.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("partial_success"))
        .andExpect(jsonPath("$.data.success_rows").value(1)).andExpect(jsonPath("$.data.failed_rows").value(1))
        .andExpect(jsonPath("$.data.error_details[0].row_no").value(2)).andReturn();
    String body = result.getResponse().getContentAsString();
    String jobId = body.substring(body.indexOf("job_id") + 8, body.indexOf(',', body.indexOf("job_id")));
    mockMvc.perform(get("/api/v1/imports/" + jobId + "/errors")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].field_name").value("amount"));
  }

  @Test
  void repeatedBankStatementImportIsSkipped() throws Exception {
    String token = loginToken();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject("select tenant_id from bank_account where id = ?", Long.class, accountId);
    String transactionNo = "TXN-DUP-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "transaction_no,transaction_date,direction,amount\n" + transactionNo + ",2026-09-09,income,10.00\n";
    MockMultipartFile file = new MockMultipartFile("file", "duplicate.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    String headers = "Authorization";
    mockMvc.perform(multipart("/api/v1/imports/bank-statements").file(file)
            .param("bank_account_id", String.valueOf(accountId)).header(headers, "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId))).andExpect(status().isOk());
    mockMvc.perform(multipart("/api/v1/imports/bank-statements").file(file)
            .param("bank_account_id", String.valueOf(accountId)).header(headers, "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId))).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success_rows").value(0)).andExpect(jsonPath("$.data.skipped_rows").value(1));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
        "select count(*) from bank_transaction where tenant_id = ? and transaction_no = ?", Integer.class, tenantId, transactionNo).intValue());
  }

  @Test
  void previewsThenConfirmsBankStatementsWithoutWritingBeforeConfirmation() throws Exception {
    String token = loginToken();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject("select tenant_id from bank_account where id = ?", Long.class, accountId);
    String transactionNo = "TXN-PREVIEW-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "transaction_no,transaction_date,direction,amount\n" + transactionNo + ",2026-09-09,income,18.00\n";
    int before = jdbcTemplate.queryForObject("select count(*) from bank_transaction where tenant_id = ? and transaction_no = ?", Integer.class, tenantId, transactionNo);
    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", "preview.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.success_rows").value(1)).andExpect(jsonPath("$.data.preview_rows[0].status").value("valid"))
        .andReturn();
    org.junit.jupiter.api.Assertions.assertEquals(before, jdbcTemplate.queryForObject("select count(*) from bank_transaction where tenant_id = ? and transaction_no = ?", Integer.class, tenantId, transactionNo));
    String body = result.getResponse().getContentAsString();
    String jobId = body.substring(body.indexOf("job_id") + 8, body.indexOf(',', body.indexOf("job_id")));
    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.preview_rows[0].status").value("confirmed"));
    org.junit.jupiter.api.Assertions.assertEquals(before + 1, jdbcTemplate.queryForObject("select count(*) from bank_transaction where tenant_id = ? and transaction_no = ?", Integer.class, tenantId, transactionNo));
  }

  @Test
  void retriesFailedPreviewRowBeforeConfirmation() throws Exception {
    String token = loginToken();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject("select tenant_id from bank_account where id = ?", Long.class, accountId);
    String transactionNo = "TXN-RETRY-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "transaction_no,transaction_date,direction,amount\n" + transactionNo + ",2026-09-09,income,bad-number\n";
    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", "retry.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.failed_rows").value(1)).andReturn();
    String body = result.getResponse().getContentAsString();
    String jobId = body.substring(body.indexOf("job_id") + 8, body.indexOf(',', body.indexOf("job_id")));
    String retry = "{\"rows\":[{\"row_no\":2,\"transaction_no\":\"" + transactionNo + "\",\"transaction_date\":\"2026-09-09\",\"direction\":\"income\",\"amount\":\"22.00\"}]}";
    mockMvc.perform(post("/api/v1/imports/" + jobId + "/retry-errors").contentType(MediaType.APPLICATION_JSON).content(retry)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.failed_rows").value(0)).andExpect(jsonPath("$.data.preview_rows[0].status").value("retry_success"));
    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("success"));
  }

  @Test
  void previewsSixBankWorkbookWithUniquePreviewRowNumbers() throws Exception {
    String token = loginToken();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject("select tenant_id from bank_account where id = ?", Long.class, accountId);
    byte[] workbook;
    try (InputStream in = getClass().getResourceAsStream("/sample/六家银行企业网银交易明细流水格式与样本.xlsx")) {
      workbook = StreamUtils.copyToByteArray(in);
    }

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", "six-banks.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.success_rows").value(30)).andExpect(jsonPath("$.data.failed_rows").value(0))
        .andExpect(jsonPath("$.data.recognized_templates.length()").value(6)).andReturn();
    String body = result.getResponse().getContentAsString();
    int first = body.indexOf("\"row_no\":") + 9;
    int second = body.indexOf("\"row_no\":", first);
    org.junit.jupiter.api.Assertions.assertTrue(first > 8 && second > first);
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
