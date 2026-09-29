package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class BankConnectionControllerTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void createListAndTestConnection() throws Exception {
    String token = loginToken("finance01");
    Long tenantId = tenantId("finance01");
    String bankCode = "TBC" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);

    MvcResult created = mockMvc.perform(post("/api/v1/bank-connections")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bank_code\":\"" + bankCode + "\",\"bank_name\":\"测试银行\",\"remark\":\"联调\"}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bank_code").value(bankCode))
        .andExpect(jsonPath("$.data.connection_type").value("file"))
        .andReturn();
    String body = created.getResponse().getContentAsString();
    Long id = Long.valueOf(body.substring(body.indexOf("\"id\":") + 5, body.indexOf(',', body.indexOf("\"id\":"))));

    mockMvc.perform(get("/api/v1/bank-connections")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[?(@.bank_code == '" + bankCode + "')]").exists());

    String csv = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + "TX-CONN-" + bankCode + ",2026-09-09,income,100.00,连接测试客户,样本回款\n";
    mockMvc.perform(multipart("/api/v1/bank-connections/" + id + "/test")
            .file(new MockMultipartFile("file", "sample.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.parsed_rows").value(1))
        .andExpect(jsonPath("$.data.duration_ms").isNumber());

    org.junit.jupiter.api.Assertions.assertEquals("success", jdbcTemplate.queryForObject(
        "select last_test_status from bank_connection where id = ?", String.class, id));

    mockMvc.perform(multipart("/api/v1/bank-connections/" + id + "/test")
            .file(new MockMultipartFile("file", "sample.txt", "text/plain", "not a csv".getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("failed"))
        .andExpect(jsonPath("$.data.message").value("仅支持 CSV 或 XLSX 样本文件"));
  }

  @Test
  void templateCatalogListsSixBanks() throws Exception {
    String token = loginToken("finance01");
    mockMvc.perform(get("/api/v1/bank-connections/templates")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId("finance01"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(6))
        .andExpect(jsonPath("$.data[0].field_mappings").isArray())
        .andExpect(jsonPath("$.data[?(@.bank_name == '中国银行')]").exists());
  }

  @Test
  void businessRoleCannotManageConnections() throws Exception {
    String biz = loginToken("biz01");
    Long tenantId = tenantId("biz01");
    mockMvc.perform(post("/api/v1/bank-connections")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bank_code\":\"DENY01\"}")
            .header("Authorization", "Bearer " + biz).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  private Long tenantId(String loginName) {
    return jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = ?", Long.class, loginName);
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
