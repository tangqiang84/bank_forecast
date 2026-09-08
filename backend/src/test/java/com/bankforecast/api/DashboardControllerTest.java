package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DashboardControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void overviewEndpointWorks() throws Exception {
    mockMvc.perform(get("/api/v1/dashboard/overview"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.total_balance").value(2865300.00))
        .andExpect(jsonPath("$.data.top_receivables[0].contract_name").value("启明科技项目三期"))
        .andExpect(jsonPath("$.trace_id").exists());
  }
}
