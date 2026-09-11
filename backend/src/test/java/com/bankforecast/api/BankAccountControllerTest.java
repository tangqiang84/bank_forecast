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

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:account_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
    "bank-forecast.bootstrap.default-admin-password=test-password-123"
})
@AutoConfigureMockMvc
class BankAccountControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void createsListsAndClosesAccountWithoutExposingFullNumber() throws Exception {
    String token = loginToken();
    String payload = "{\"bankCode\":\"ICBC\",\"bankName\":\"工商银行\",\"accountName\":\"测试结算户\",\"accountNo\":\"6222000012345678\",\"currency\":\"CNY\",\"currentBalance\":\"100.00\"}";
    MvcResult created = mockMvc.perform(post("/api/v1/bank-accounts").contentType(MediaType.APPLICATION_JSON).content(payload)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.account_no_last4").value("5678"))
        .andExpect(jsonPath("$.data.account_no_cipher").doesNotExist()).andReturn();
    String id = created.getResponse().getContentAsString();
    id = id.substring(id.indexOf("\"id\":") + 5);
    id = id.substring(0, id.indexOf(','));
    mockMvc.perform(get("/api/v1/bank-accounts").header("Authorization", "Bearer " + token).header("X-Tenant-Id", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].idle_level").exists())
        .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.page_size").value(20))
        .andExpect(jsonPath("$.data.total").isNumber());
    mockMvc.perform(post("/api/v1/bank-accounts/" + id + "/close").header("Authorization", "Bearer " + token).header("X-Tenant-Id", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("closed"));
  }

  @Test
  void idleScanReturnsUpdatedAccounts() throws Exception {
    String token = loginToken();
    mockMvc.perform(post("/api/v1/bank-accounts/idle-scan").header("Authorization", "Bearer " + token).header("X-Tenant-Id", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.updated_accounts").isNumber()).andExpect(jsonPath("$.data.accounts").isArray());
  }

  @Test
  void returnsSingleAccountDetailWithRecentTransactionsAndAuditLogs() throws Exception {
    String token = loginToken();
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account where tenant_id = ?", Long.class, 1L);

    mockMvc.perform(get("/api/v1/bank-accounts/" + accountId)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(accountId.intValue()))
        .andExpect(jsonPath("$.data.account_no_last4").exists())
        .andExpect(jsonPath("$.data.transactions").isArray())
        .andExpect(jsonPath("$.data.transaction_count").isNumber())
        .andExpect(jsonPath("$.data.audit_logs").isArray());
  }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"test-password-123\"}"))
        .andExpect(status().isOk()).andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
