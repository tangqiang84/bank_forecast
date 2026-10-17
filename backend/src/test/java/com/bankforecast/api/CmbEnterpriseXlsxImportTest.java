package com.bankforecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankforecast.common.XlsxWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
class CmbEnterpriseXlsxImportTest {

  private static final String TEST_PASSWORD = "test-password-123";
  private static final String XLSX_CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final String REPLAY_PROPERTY = "cmb.replay.file";

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
    int expectedRows = 7;

    MvcResult preview = mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", "cmb-enterprise.xlsx", XLSX_CONTENT_TYPE, workbook))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.file_format").value("xlsx"))
        .andExpect(jsonPath("$.data.recognized_templates[0].bank_name").value("招商银行"))
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
            .file(new MockMultipartFile("file", "cmb-enterprise.xlsx", XLSX_CONTENT_TYPE, workbook))
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
    List<String> first = cmbRow("2026-02-10", "09:00:01", "2026-02-10", "支付", "88.10", "",
        "911.90", "相同摘要", "CMB-DIRECT", "相同用途", "直接导入对手", "CP-DIRECT", "招商银行深圳分行");
    List<String> second = new ArrayList<>(first);
    second.set(4, "09:00:02");
    byte[] workbook = syntheticWorkbook(List.of(first, second));

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "cmb-direct.xlsx", XLSX_CONTENT_TYPE, workbook))
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
    for (int i = 1; i <= 7; i++) rows.add(List.of("企业网银元数据说明" + i));
    rows.add(List.of(""));
    rows.add(Arrays.asList("账号", "账号名称", "币种", "交易日", "交易时间", "起息日", "交易类型",
        "借方金额", "贷方金额", "余额", "摘要", "流水号", "流程实例号", "业务名称", "用途", "业务参考号", "业务摘要",
        "其它摘要", "收(付)方分行名", "收(付)方名称", "收(付)方账号", "收(付)方开户行行号", "收(付)方开户行名",
        "收(付)方开户行地址", "母(子)公司账号分行名", "母(子)公司账号", "母(子)公司名称", "信息标志", "有否附件信息",
        "冲账标志", "扩展摘要", "交易分析码", "票据号", "商务支付订单号", "内部编号", "公司一卡通号"));
    rows.addAll(dataRows);
    return XlsxWriter.write("Sheet1", rows);
  }

  private List<List<String>> defaultDataRows() {
    List<List<String>> rows = new ArrayList<>();
    rows.add(cmbRow("2026-02-06", "09:00:01", "2026-02-09", "支付", "100.10", "", "899.90",
        "采购付款", "CMB-SRC-001", "货款", "对手甲", "CP-001", "招商银行深圳分行"));
    rows.add(cmbRow("2026-02-07", "10:00:02", "2026-02-07", "收款", "", "200.20", "1100.10",
        "销售回款", "CMB-SRC-002", "回款", "对手乙", "CP-002", "招商银行深圳分行"));
    rows.add(cmbRow("2026-02-08", "11:00:03", "2026-02-08", "支付", "300.00", "", "800.10",
        "服务费", "CMB-SRC-003", "服务费", "对手丙", "CP-003", "招商银行广州分行"));
    rows.add(cmbRow("2026-02-09", "12:00:04", "2026-02-09", "收款", "", "400.00", "1200.10",
        "项目回款", "CMB-SRC-004", "回款", "对手丁", "CP-004", "招商银行广州分行"));
    rows.add(cmbRow("2026-02-10", "13:00:05", "2026-02-10", "支付", "500.00", "", "700.10",
        "原摘要", "CMB-SRC-005", "付款", "对手戊", "CP-005", "招商银行北京分行"));
    rows.add(cmbRow("2026-02-10", "14:00:06", "2026-02-10", "支付", "500.00", "", "700.10",
        "原摘要", "CMB-SRC-005", "付款", "对手戊", "CP-005", "招商银行北京分行"));
    rows.add(cmbRow("2026-02-10", "15:00:07", "2026-02-10", "收款", "", "600.00", "1300.10",
        "新摘要", "CMB-SRC-005", "回款", "对手戊", "CP-005", "招商银行北京分行"));
    return rows;
  }

  private List<String> cmbRow(String tradeDate, String tradeTime, String valueDate, String type,
      String debit, String credit, String balance, String summary, String serialNo, String purpose,
      String cpName, String cpAccount, String cpBank) {
    return Arrays.asList("755999888777666", "深圳分公司人民币户", "人民币",
        tradeDate, tradeTime, valueDate, type, debit, credit, balance, summary, serialNo,
        "PI-0001", "企业支付", purpose, "BR-0001", "业务摘要一", "其它摘要一", "深圳分行", cpName, cpAccount,
        "308584000013", cpBank, "深圳市南山区", "总行营业部", "755000000000001", "母公司的名字", "0", "无",
        "0", "扩展摘要一", "A001", "票据一", "订单一", "内部一", "一卡通一");
  }
}
