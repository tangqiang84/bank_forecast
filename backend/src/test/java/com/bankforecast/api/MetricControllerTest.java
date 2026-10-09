package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** P2-4 试点数据采集：指标快照采集、历史趋势和验收指标。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class MetricControllerTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void collectIsIdempotentPerDayAndSeriesReturnsValues() throws Exception {
    String token = loginToken("finance01");
    String today = LocalDate.now().toString();

    mockMvc.perform(post("/api/v1/metrics/snapshots/collect")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.snapshot_date").value(today))
        .andExpect(jsonPath("$.data.collected").value(13));

    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from metric_snapshot where snapshot_date = ?", Integer.class, today);
    org.junit.jupiter.api.Assertions.assertEquals(13, count.intValue());

    // 同日重复采集覆盖，行数不变
    mockMvc.perform(post("/api/v1/metrics/snapshots/collect")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"date\":\"" + today + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.collected").value(13));
    Integer countAgain = jdbcTemplate.queryForObject(
        "select count(*) from metric_snapshot where snapshot_date = ?", Integer.class, today);
    org.junit.jupiter.api.Assertions.assertEquals(count, countAgain);

    mockMvc.perform(get("/api/v1/metrics/snapshots?metric_code=import_success_rate")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].snapshot_date").exists());

    mockMvc.perform(get("/api/v1/metrics/snapshots?metric_code=not_a_metric")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  void pilotAcceptanceReturnsIndicatorsWithThresholds() throws Exception {
    String token = loginToken("finance01");
    mockMvc.perform(get("/api/v1/metrics/pilot-acceptance")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.indicators.length()").value(6))
        .andExpect(jsonPath("$.data.indicators[0].metric_code").value("import_success_rate"))
        .andExpect(jsonPath("$.data.indicators[0].threshold").value(95))
        .andExpect(jsonPath("$.data.indicators[0].pass").isBoolean())
        .andExpect(jsonPath("$.data.amounts.length()").value(7))
        .andExpect(jsonPath("$.data.as_of").exists());
  }

  @Test
  void metricsRequirePermission() throws Exception {
    String token = loginToken("biz01");
    mockMvc.perform(get("/api/v1/metrics/pilot-acceptance")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
    mockMvc.perform(post("/api/v1/metrics/snapshots/collect")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
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
