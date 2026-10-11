package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:mysql://127.0.0.1:${TEST_DB_PORT:3307}/bank_forecast_test_dashboard?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
})
@AutoConfigureMockMvc
class DashboardControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void overviewRequiresLogin() throws Exception {
    mockMvc.perform(get("/api/v1/dashboard/overview"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(41001));
  }

  @Test
  void overviewEndpointWorks() throws Exception {
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"test-password-123\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String body = login.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    token = token.substring(0, token.indexOf('"'));

    mockMvc.perform(get("/api/v1/dashboard/overview")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.total_balance").value(2865300.00))
        .andExpect(jsonPath("$.data.top_receivables").isEmpty())
        .andExpect(jsonPath("$.trace_id").exists());
  }
}
