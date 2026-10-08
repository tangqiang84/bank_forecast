package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.StreamUtils;

/** P2-1 真实文件回放：回放样本经预览接口全链路验证，不落正式表。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class BankFileReplayApiTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void replaysSixBankXlsxFixturesThroughPreview() throws Exception {
    replayXlsx("replay-boc-column-variants.xlsx", "中国银行", 3);
    replayXlsx("replay-icbc-date-formats.xlsx", "工商银行", 3);
    replayXlsx("replay-cgb-multi-currency.xlsx", "广发银行", 3);
    replayXlsx("replay-pab-paged-header.xlsx", "平安银行", 4);
    replayXlsx("replay-bosc-date-formats.xlsx", "上海银行", 2);
    replayXlsx("replay-suzb-standard.xlsx", "苏州银行", 2);
  }

  @Test
  void replaysEncodingVariantCsvFixturesThroughPreview() throws Exception {
    replayCsv("replay-boc-gb18030.csv", 3);
    replayCsv("replay-icbc-utf16le.csv", 2);
  }

  private void replayXlsx(String resource, String bankName, int expectedRows) throws Exception {
    preview(resource, expectedRows)
        .andExpect(jsonPath("$.data.file_format").value("xlsx"))
        .andExpect(jsonPath("$.data.recognized_templates.length()").value(1))
        .andExpect(jsonPath("$.data.recognized_templates[0].bank_name").value(bankName))
        .andExpect(jsonPath("$.data.recognized_templates[0].status").value("recognized"));
  }

  private void replayCsv(String resource, int expectedRows) throws Exception {
    preview(resource, expectedRows)
        .andExpect(jsonPath("$.data.file_format").value("csv"));
  }

  private org.springframework.test.web.servlet.ResultActions preview(String resource, int expectedRows)
      throws Exception {
    String token = loginToken();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from bank_account where id = ?", Long.class, accountId);
    byte[] content;
    try (InputStream in = getClass().getResourceAsStream("/replay/" + resource)) {
      content = StreamUtils.copyToByteArray(in);
    }
    return mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", resource,
                resource.endsWith(".xlsx")
                    ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    : "text/csv",
                content))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.success_rows").value(expectedRows))
        .andExpect(jsonPath("$.data.failed_rows").value(0));
  }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"" + TEST_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
