package com.bankforecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.StreamUtils;

/**
 * P2-5 真实数据验收（演示数据口径）：导入成功率、模板识别率、自动匹配率、异常闭环
 * 和常用查询性能的端到端验收套件。数值阈值结论记录在 docs/reports 验收报告。
 * 样本按固定流水号导入一次（@BeforeAll），各用例共享。
 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PilotAcceptanceTest {

  private static final String TEST_PASSWORD = "test-password-123";
  private static final long QUERY_TIME_LIMIT_MS = 3000;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  private String token;
  private Long tenantId;
  private Long accountId;

  @BeforeAll
  void setUp() throws Exception {
    token = loginToken();
    accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from bank_account where id = ?", Long.class, accountId);

    mockMvc.perform(multipart("/api/v1/imports/contracts")
            .file(new MockMultipartFile("file", "contracts.csv", "text/csv", resource("/sample/匹配合同样本.csv")))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success_rows").value(2));
    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "statements.csv", "text/csv", resource("/sample/匹配流水样本.csv")))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(3))
        .andExpect(jsonPath("$.data.failed_rows").value(0));
  }

  @Test
  void importSuccessRateAndTemplateRecognition() throws Exception {
    // 导入成功率：setup 中匹配流水样本 3/3 成功（100%），合同样本 2/2 成功
    Integer failed = jdbcTemplate.queryForObject(
        "select coalesce(sum(failed_rows), 0) from import_job where tenant_id = ? and file_name in ('statements.csv', 'contracts.csv')",
        Integer.class, tenantId);
    assertEquals(0, failed.intValue());

    // 模板识别率：六家银行样本工作簿 6/6 识别并写入 templates_recognized
    MvcResult preview = mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", "six-banks.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                resource("/sample/六家银行企业网银交易明细流水格式与样本.xlsx")))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.recognized_templates.length()").value(6))
        .andReturn();
    String body = preview.getResponse().getContentAsString();
    String jobId = body.substring(body.indexOf("job_id") + 8, body.indexOf(',', body.indexOf("job_id")));
    assertEquals(6, jdbcTemplate.queryForObject(
        "select templates_recognized from import_job where id = ?", Integer.class, Long.valueOf(jobId)).intValue());
  }

  @Test
  void autoMatchAndExceptionClosure() throws Exception {
    mockMvc.perform(post("/api/v1/matching/receivables/run")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());

    // 自动匹配：精确样本直接 matched
    Long exactTxId = jdbcTemplate.queryForObject(
        "select id from bank_transaction where tenant_id = ? and transaction_no = 'MATCH-TX-EXACT-001'",
        Long.class, tenantId);
    assertEquals("matched", jdbcTemplate.queryForObject(
        "select match_status from bank_transaction where id = ?", String.class, exactTxId));
    // 部分收款样本生成推荐匹配，人工确认后闭环
    Long partialResultId = jdbcTemplate.queryForObject(
        "select mr.id from match_result mr join bank_transaction t on t.id = mr.bank_transaction_id "
            + "where mr.tenant_id = ? and t.transaction_no = 'MATCH-TX-PARTIAL-001' and mr.match_status = 'suggested' and mr.deleted_at is null",
        Long.class, tenantId);
    assertNotNull(partialResultId);
    mockMvc.perform(post("/api/v1/matching/results/" + partialResultId + "/confirm")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    // 未知收款样本生成异常，处理完成后闭环
    Long exceptionId = jdbcTemplate.queryForObject(
        "select e.id from exception_case e join bank_transaction t on t.id = e.source_id "
            + "where e.tenant_id = ? and t.transaction_no = 'MATCH-TX-UNKNOWN-001' and e.exception_type = 'unknown_receipt' and e.deleted_at is null",
        Long.class, tenantId);
    assertNotNull(exceptionId);
    mockMvc.perform(post("/api/v1/matching/exceptions/" + exceptionId + "/resolve")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"验收确认为客户预付款\"}"))
        .andExpect(status().isOk());
    assertEquals("resolved", jdbcTemplate.queryForObject(
        "select status from exception_case where id = ?", String.class, exceptionId));
  }

  @Test
  void commonQueriesRespondWithinThreeSeconds() throws Exception {
    String[][] endpoints = {
        {"/api/v1/dashboard/overview", "驾驶舱总览"},
        {"/api/v1/bank-transactions?page=1&page_size=20", "流水列表"},
        {"/api/v1/matching/results?page=1&page_size=20", "匹配结果列表"}};
    for (String[] endpoint : endpoints) {
      long start = System.currentTimeMillis();
      mockMvc.perform(get(endpoint[0])
              .header("Authorization", "Bearer " + token)
              .header("X-Tenant-Id", String.valueOf(tenantId)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.code").value(0));
      long elapsed = System.currentTimeMillis() - start;
      assertTrue(elapsed < QUERY_TIME_LIMIT_MS,
          endpoint[1] + "查询耗时 " + elapsed + "ms 超过 " + QUERY_TIME_LIMIT_MS + "ms");
    }
  }

  @Test
  void pilotAcceptanceMetricsAvailable() throws Exception {
    mockMvc.perform(get("/api/v1/metrics/pilot-acceptance")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.indicators.length()").value(6))
        .andExpect(jsonPath("$.data.indicators[0].value").isNumber());
  }

  private byte[] resource(String name) throws Exception {
    try (InputStream in = getClass().getResourceAsStream(name)) {
      return StreamUtils.copyToByteArray(in);
    }
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
