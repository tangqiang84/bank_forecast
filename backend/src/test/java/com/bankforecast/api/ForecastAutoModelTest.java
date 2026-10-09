package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.hamcrest.Matchers.containsString;

/** 分析服务模型层重构（2026-10-09）：auto 推荐模型、显式版本和置信区间入库。analytics 用 MockRestServiceServer 隔离。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class ForecastAutoModelTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RestTemplate analyticsRestTemplate;

  private MockRestServiceServer analyticsServer;

  @BeforeEach
  void setUp() {
    analyticsServer = MockRestServiceServer.bindTo(analyticsRestTemplate).build();
  }

  @Test
  void autoModelVersionUsesRecommendedAndStoresConfidenceInterval() throws Exception {
    String token = loginToken();
    seedTransactions();
    analyticsServer.expect(requestTo(containsString("/forecast/evaluate")))
        .andRespond(withSuccess("{\"code\":0,\"message\":\"ok\",\"data\":{\"evaluations\":[],\"recommended_model_version\":\"v3\"}}", MediaType.APPLICATION_JSON));
    analyticsServer.expect(requestTo(containsString("/forecast/cashflow")))
        .andRespond(withSuccess("{\"code\":0,\"message\":\"ok\",\"data\":{\"method\":\"holt-linear\",\"model_version\":\"v3\",\"baseline\":100.0,\"trend_step\":1.0,"
            + "\"forecast_values\":[1,2,3,4,5,6,7],\"confidence_lower\":[0,1,2,3,4,5,6],\"confidence_upper\":[2,3,4,5,6,7,8],"
            + "\"backtest\":{\"mae\":12.5,\"rmse\":15.0,\"points\":7}}}", MediaType.APPLICATION_JSON));

    MvcResult result = mockMvc.perform(post("/api/v1/forecast/cashflow/jobs?horizon=7&model_version=auto")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.job.model_version").value("v3"))
        .andExpect(jsonPath("$.data.job.model_name").value("holt-linear"))
        .andExpect(jsonPath("$.data.results[0].ci_lower").exists())
        .andExpect(jsonPath("$.data.results[0].ci_upper").exists())
        .andReturn();
    analyticsServer.verify();
    String body = result.getResponse().getContentAsString();
    String jobId = body.substring(body.indexOf("\"id\":") + 5, body.indexOf(',', body.indexOf("\"id\":")));
    String backtest = jdbcTemplate.queryForObject(
        "select backtest_json from forecast_job where id = ?", String.class, Long.valueOf(jobId));
    org.junit.jupiter.api.Assertions.assertTrue(backtest != null && backtest.contains("\"mae\":12.5"));
  }

  @Test
  void explicitModelVersionSkipsEvaluate() throws Exception {
    String token = loginToken();
    seedTransactions();
    analyticsServer.expect(requestTo(containsString("/forecast/cashflow")))
        .andRespond(withSuccess("{\"code\":0,\"message\":\"ok\",\"data\":{\"method\":\"weekly-seasonal-naive\",\"model_version\":\"v2\",\"baseline\":100.0,\"trend_step\":0.0,"
            + "\"forecast_values\":[1,1,1],\"confidence_lower\":[0,0,0],\"confidence_upper\":[2,2,2],"
            + "\"backtest\":{\"mae\":1.0,\"rmse\":1.0,\"points\":3}}}", MediaType.APPLICATION_JSON));

    mockMvc.perform(post("/api/v1/forecast/cashflow/jobs?horizon=3&model_version=v2")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.job.model_version").value("v2"));
    analyticsServer.verify();

    mockMvc.perform(post("/api/v1/forecast/cashflow/jobs?horizon=3&model_version=v9")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(42001));
  }

  @Test
  void analyticsFailureMarksJobFailed() throws Exception {
    String token = loginToken();
    seedTransactions();
    analyticsServer.expect(requestTo(containsString("/forecast/cashflow")))
        .andRespond(withServerError());

    mockMvc.perform(post("/api/v1/forecast/cashflow/jobs?horizon=3")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isInternalServerError());
    Integer failed = jdbcTemplate.queryForObject(
        "select count(*) from forecast_job where status = 'failed' and error_message is not null", Integer.class);
    org.junit.jupiter.api.Assertions.assertTrue(failed != null && failed > 0);
  }

  private void seedTransactions() {
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    String marker = String.valueOf(System.nanoTime());
    jdbcTemplate.update(
        "insert into import_job (tenant_id, job_type, source_type, file_name, status) values (?, 'bank_statement', 'file', ?, 'success')",
        tenantId, "forecast-seed-" + marker + ".csv");
    Long jobId = jdbcTemplate.queryForObject(
        "select id from import_job where tenant_id = ? and file_name = ?", Long.class, tenantId, "forecast-seed-" + marker + ".csv");
    for (int i = 0; i < 3; i++) {
      jdbcTemplate.update(
          "insert into bank_transaction (tenant_id, bank_account_id, import_job_id, transaction_no, transaction_date, direction, amount, match_status) values (?, ?, ?, ?, ?, 'income', ?, 'unmatched')",
          tenantId, accountId, jobId, "FC-SEED-" + marker + "-" + i,
          java.sql.Date.valueOf(java.time.LocalDate.now().minusDays(3 - i)),
          new java.math.BigDecimal("100.00"));
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
