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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class AuthControllerTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void loginAndMeWorks() throws Exception {
    MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"" + TEST_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.access_token").exists())
        .andReturn();

    String body = loginResult.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    token = token.substring(0, token.indexOf('"'));
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = ?", Long.class, "finance01");

    mockMvc.perform(get("/api/v1/auth/me")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.login_name").value("finance01"));
  }

  @Test
  void loginRejectsWrongPassword() throws Exception {
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"bad\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(41006));
  }
}
