package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** P2-3 导入质量：弱指纹去重、重复确认和导入质量指标。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class ImportQualityTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void weakDuplicateSuspectedAndSkippedUnlessForced() throws Exception {
    String token = loginToken("finance01");
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from bank_account where id = ?", Long.class, accountId);
    String marker = UUID.randomUUID().toString().substring(0, 8);
    String baseRow = ",2026-09-09,income,66.00,,弱指纹对手" + marker + ",弱指纹摘要" + marker;

    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "base.csv", "text/csv",
                ("transaction_no,transaction_date,direction,amount,balance_after,counterparty_name,summary\n"
                    + "WF-BASE-" + marker + baseRow + "\n").getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success_rows").value(1));

    // 不同流水号但日期/金额/对手方/摘要一致 → 疑似重复
    MvcResult preview = mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", "dup.csv", "text/csv",
                ("transaction_no,transaction_date,direction,amount,balance_after,counterparty_name,summary\n"
                    + "WF-DUP-" + marker + baseRow + "\n").getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.success_rows").value(0))
        .andExpect(jsonPath("$.data.duplicate_suspected_rows").value(1))
        .andExpect(jsonPath("$.data.preview_rows[0].status").value("duplicate_suspect"))
        .andReturn();
    String body = preview.getResponse().getContentAsString();
    String jobId = body.substring(body.indexOf("job_id") + 8, body.indexOf(',', body.indexOf("job_id")));

    int before = countTransactions(tenantId, "WF-DUP-" + marker);

    // 默认确认不导入疑似重复行
    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    org.junit.jupiter.api.Assertions.assertEquals(before, countTransactions(tenantId, "WF-DUP-" + marker));

    // 已确认任务不能重复确认
    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(44015));
  }

  @Test
  void confirmWithIncludeDuplicatesForceImports() throws Exception {
    String token = loginToken("finance01");
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from bank_account where id = ?", Long.class, accountId);
    String marker = UUID.randomUUID().toString().substring(0, 8);
    String baseRow = ",2026-09-09,expense,88.00,,强制对手" + marker + ",强制摘要" + marker;

    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "base.csv", "text/csv",
                ("transaction_no,transaction_date,direction,amount,balance_after,counterparty_name,summary\n"
                    + "WF2-BASE-" + marker + baseRow + "\n").getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());

    MvcResult preview = mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", "dup.csv", "text/csv",
                ("transaction_no,transaction_date,direction,amount,balance_after,counterparty_name,summary\n"
                    + "WF2-DUP-" + marker + baseRow + "\n").getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.preview_rows[0].status").value("duplicate_suspect"))
        .andReturn();
    String body = preview.getResponse().getContentAsString();
    String jobId = body.substring(body.indexOf("job_id") + 8, body.indexOf(',', body.indexOf("job_id")));
    int rowNo = jdbcTemplate.queryForObject(
        "select row_no from import_preview_row where import_job_id = ? and status = 'duplicate_suspect'",
        Integer.class, Long.valueOf(jobId));

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"include_duplicates\":[" + rowNo + "]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(1));
    org.junit.jupiter.api.Assertions.assertEquals(1, countTransactions(tenantId, "WF2-DUP-" + marker));
  }

  @Test
  void inFileWeakDuplicateFlagged() throws Exception {
    String token = loginToken("finance01");
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from bank_account where id = ?", Long.class, accountId);
    String marker = UUID.randomUUID().toString().substring(0, 8);
    String csv = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + "WF3-A-" + marker + ",2026-09-09,income,10.00,同文对手" + marker + ",同文摘要" + marker + "\n"
        + "WF3-B-" + marker + ",2026-09-09,income,10.00,同文对手" + marker + ",同文摘要" + marker + "\n";

    mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", "infile.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success_rows").value(1))
        .andExpect(jsonPath("$.data.duplicate_suspected_rows").value(1))
        .andExpect(jsonPath("$.data.preview_rows[1].status").value("duplicate_suspect"));
  }

  @Test
  void qualitySummaryAggregatesAndRequiresPermission() throws Exception {
    String token = loginToken("finance01");
    mockMvc.perform(get("/api/v1/imports/quality-summary?days=30")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.days").value(30))
        .andExpect(jsonPath("$.data.job_count").isNumber())
        .andExpect(jsonPath("$.data.duplicate_suspected_rows").isNumber());

    String bizToken = loginToken("biz01");
    mockMvc.perform(get("/api/v1/imports/quality-summary")
            .header("Authorization", "Bearer " + bizToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  private int countTransactions(Long tenantId, String transactionNo) {
    return jdbcTemplate.queryForObject(
        "select count(*) from bank_transaction where tenant_id = ? and transaction_no = ?",
        Integer.class, tenantId, transactionNo);
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
