package com.bankforecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
@Transactional
class NbcbEnterpriseXlsImportTest {

  private static final String TEST_PASSWORD = "test-password-123";
  private static final String XLS_CONTENT_TYPE = "application/vnd.ms-excel";
  private static final String REPLAY_PROPERTY = "nbcb.replay.file";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void previewsConfirmsAndSkipsSameEnterpriseWorkbook() throws Exception {
    String replayFile = System.getProperty(REPLAY_PROPERTY);
    if (replayFile != null && !replayFile.isBlank()) {
      Assumptions.assumeTrue(Files.exists(Path.of(replayFile)), "回放文件不存在，跳过外部回放");
    }
    String token = loginToken();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from bank_account where id = ?", Long.class, accountId);
    byte[] workbook = replayWorkbook();
    int expectedRows = 4;

    MvcResult preview = mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", "nbcb-enterprise.xls", XLS_CONTENT_TYPE, workbook))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.file_format").value("xls"))
        .andExpect(jsonPath("$.data.recognized_templates[0].bank_name").value("宁波银行"))
        .andExpect(jsonPath("$.data.success_rows").value(expectedRows))
        .andExpect(jsonPath("$.data.failed_rows").value(0))
        .andExpect(jsonPath("$.data.duplicate_suspected_rows").value(0))
        .andExpect(jsonPath("$.data.preview_rows.length()").value(expectedRows))
        .andReturn();
    long jobId = jobId(preview);

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(expectedRows))
        .andExpect(jsonPath("$.data.skipped_rows").value(0));

    assertEquals(expectedRows, transactionCount(tenantId, jobId));
    assertEquals(expectedRows, distinctTransactionNos(tenantId, jobId));

    MvcResult repeated = mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "nbcb-enterprise.xls", XLS_CONTENT_TYPE, workbook))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.success_rows").value(0))
        .andExpect(jsonPath("$.data.skipped_rows").value(expectedRows))
        .andReturn();
    long repeatedJobId = jobId(repeated);

    assertEquals(expectedRows, transactionCount(tenantId, jobId, repeatedJobId));
    assertEquals(expectedRows, distinctTransactionNos(tenantId, jobId, repeatedJobId));
  }

  @Test
  void directImportKeepsDifferentStandardNosWithSameLegacyWeakFields() throws Exception {
    String token = loginToken();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from bank_account where id = ?", Long.class, accountId);
    List<String> first = nbcbRow("NBCB-DIRECT", "2026-02-10", "09:00:01", "88.10", "",
        "911.90", "CP-DIRECT", "直接导入对手", "宁波银行海曙支行", "相同摘要", "相同备注");
    List<String> second = new ArrayList<>(first);
    second.set(3, "09:00:02");
    byte[] workbook = syntheticWorkbook(List.of(first, second));

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "nbcb-direct.xls", XLS_CONTENT_TYPE, workbook))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.success_rows").value(2))
        .andExpect(jsonPath("$.data.failed_rows").value(0))
        .andExpect(jsonPath("$.data.skipped_rows").value(0))
        .andExpect(jsonPath("$.data.duplicate_suspected_rows").value(0))
        .andReturn();
    long jobId = jobId(result);

    assertEquals(2, transactionCount(tenantId, jobId));
    assertEquals(2, distinctTransactionNos(tenantId, jobId));
  }

  private long transactionCount(Long tenantId, Long... jobIds) {
    if (jobIds.length == 1) {
      return jdbcTemplate.queryForObject(
          "select count(*) from bank_transaction where tenant_id = ? and import_job_id = ?",
          Long.class, tenantId, jobIds[0]);
    }
    return jdbcTemplate.queryForObject(
        "select count(*) from bank_transaction where tenant_id = ? and import_job_id in (?, ?)",
        Long.class, tenantId, jobIds[0], jobIds[1]);
  }

  private long distinctTransactionNos(Long tenantId, Long... jobIds) {
    if (jobIds.length == 1) {
      return jdbcTemplate.queryForObject(
          "select count(distinct transaction_no) from bank_transaction where tenant_id = ? and import_job_id = ?",
          Long.class, tenantId, jobIds[0]);
    }
    return jdbcTemplate.queryForObject(
        "select count(distinct transaction_no) from bank_transaction where tenant_id = ? and import_job_id in (?, ?)",
        Long.class, tenantId, jobIds[0], jobIds[1]);
  }

  private long jobId(MvcResult result) throws Exception {
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return body.path("data").path("job_id").asLong();
  }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"" + TEST_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return body.path("data").path("access_token").asText();
  }

  private byte[] replayWorkbook() throws IOException {
    String replayFile = System.getProperty(REPLAY_PROPERTY);
    if (replayFile != null && !replayFile.isBlank()) return Files.readAllBytes(Path.of(replayFile));
    return syntheticWorkbook(defaultDataRows());
  }

  private byte[] syntheticWorkbook(List<List<String>> dataRows) {
    List<List<String>> rows = new ArrayList<>();
    for (int i = 1; i <= 3; i++) rows.add(List.of("账户信息说明" + i));
    rows.add(Arrays.asList("流水号", "交易账号", "交易日期", "交易时间", "币种", "交易网点",
        "借方金额", "贷方金额", "账户余额", "对方账号", "对方户名", "对方开户行", "凭证号", "摘要", "备注"));
    rows.addAll(dataRows);
    try (HSSFWorkbook workbook = new HSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Sheet1");
      for (int i = 0; i < rows.size(); i++) {
        Row row = sheet.createRow(i);
        for (int j = 0; j < rows.get(i).size(); j++) row.createCell(j).setCellValue(rows.get(i).get(j));
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private List<List<String>> defaultDataRows() {
    List<List<String>> rows = new ArrayList<>();
    rows.add(nbcbRow("NBCB-SRC-001", "2026-02-06", "09:00:01", "100.10", "", "899.90",
        "CP-001", "对手甲", "宁波银行海曙支行", "采购付款", "货款"));
    rows.add(nbcbRow("NBCB-SRC-002", "2026-02-07", "10:00:02", "", "200.20", "1100.10",
        "CP-002", "对手乙", "宁波银行海曙支行", "销售回款", "回款"));
    rows.add(nbcbRow("NBCB-SRC-003", "2026-02-08", "11:00:03", "300.00", "0.00", "800.10",
        "CP-003", "对手丙", "宁波银行江北支行", "服务费", "服务费"));
    rows.add(nbcbRow("NBCB-SRC-004", "2026-02-09", "12:00:04", "400.00", "", "1200.10",
        "CP-004", "对手丁", "宁波银行江北支行", "原摘要", ""));
    return rows;
  }

  private List<String> nbcbRow(String serialNo, String tradeDate, String tradeTime, String debit,
      String credit, String balance, String cpAccount, String cpName, String cpBank,
      String summary, String remark) {
    return new ArrayList<>(Arrays.asList(serialNo, "900111222333444", tradeDate, tradeTime, "人民币",
        "宁波分行营业部", debit, credit, balance, cpAccount, cpName, cpBank, "--", summary, remark));
  }
}
