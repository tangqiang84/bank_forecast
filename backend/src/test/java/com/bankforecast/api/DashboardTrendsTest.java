package com.bankforecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 驾驶舱主指标 7/30 天趋势（2026-10-10）：趋势接口结构、口径不变量和越权。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class DashboardTrendsTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @SuppressWarnings("unchecked")
  void trendsSeriesInvariants() throws Exception {
    String token = loginToken("finance01");
    MvcResult result = mockMvc.perform(get("/api/v1/dashboard/trends?days=7")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.days").value(7))
        .andExpect(jsonPath("$.data.series.length()").value(7))
        .andReturn();
    Map<String, Object> data = (Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper()
        .readValue(result.getResponse().getContentAsString(), Map.class).get("data");
    List<Map<String, Object>> series = (List<Map<String, Object>>) data.get("series");

    for (int i = 0; i < series.size(); i++) {
      Map<String, Object> point = series.get(i);
      // net_inflow = income − expense
      BigDecimal income = new BigDecimal(String.valueOf(point.get("income")));
      BigDecimal expense = new BigDecimal(String.valueOf(point.get("expense")));
      assertEquals(0, income.subtract(expense)
          .compareTo(new BigDecimal(String.valueOf(point.get("net_inflow")))));
      if (i > 0) {
        // 日期升序；balance(d) = balance(d-1) + net(d)
        Map<String, Object> previous = series.get(i - 1);
        assertTrue(String.valueOf(point.get("date")).compareTo(String.valueOf(previous.get("date"))) > 0);
        BigDecimal balance = new BigDecimal(String.valueOf(point.get("balance")));
        BigDecimal previousBalance = new BigDecimal(String.valueOf(previous.get("balance")));
        assertEquals(0, previousBalance.add(new BigDecimal(String.valueOf(point.get("net_inflow"))))
            .compareTo(balance));
      }
      assertNotNull(point.get("open_exceptions"));
    }
    // 末日余额与当前账户余额一致
    Map<String, Object> last = series.get(series.size() - 1);
    BigDecimal currentBalance = jdbcTemplate.queryForObject(
        "select coalesce(sum(current_balance), 0) from bank_account where deleted_at is null", BigDecimal.class);
    assertEquals(0, currentBalance.compareTo(new BigDecimal(String.valueOf(last.get("balance")))));
  }

  @Test
  void trendsValidationAndPermission() throws Exception {
    String token = loginToken("finance01");
    mockMvc.perform(get("/api/v1/dashboard/trends?days=91")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));

    String bizToken = loginToken("biz01");
    mockMvc.perform(get("/api/v1/dashboard/trends")
            .header("Authorization", "Bearer " + bizToken))
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
