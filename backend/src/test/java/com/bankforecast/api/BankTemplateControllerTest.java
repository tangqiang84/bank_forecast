package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** P2-2 模板配置中心：租户级表头映射 CRUD、越权和别名覆盖解析。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class BankTemplateControllerTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void templateMappingCrudAndOverview() throws Exception {
    String token = loginToken("finance01");
    String header = "业务编号-" + UUID.randomUUID().toString().substring(0, 8);

    mockMvc.perform(get("/api/v1/bank-templates").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.templates.length()").value(7))
        .andExpect(jsonPath("$.data.fields.length()").value(10))
        .andExpect(jsonPath("$.data.mappings").isArray());

    MvcResult created = mockMvc.perform(post("/api/v1/bank-templates/mappings")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"header\":\"" + header + "\",\"field\":\"transaction_no\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.header").value(header))
        .andExpect(jsonPath("$.data.field").value("transaction_no"))
        .andReturn();
    String body = created.getResponse().getContentAsString();
    String id = body.substring(body.indexOf("\"id\":") + 5, body.indexOf(',', body.indexOf("\"id\":")));

    mockMvc.perform(post("/api/v1/bank-templates/mappings")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"header\":\"" + header + "\",\"field\":\"summary\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));

    mockMvc.perform(post("/api/v1/bank-templates/mappings")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"header\":\"另一个表头\",\"field\":\"not_a_field\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));

    mockMvc.perform(put("/api/v1/bank-templates/mappings/" + id)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"header\":\"" + header + "\",\"field\":\"summary\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.field").value("summary"));

    mockMvc.perform(delete("/api/v1/bank-templates/mappings/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deleted").value(true));
  }

  @Test
  void tenantAliasEnablesUnknownHeaderCsvPreview() throws Exception {
    String token = loginToken("finance01");
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from bank_account where id = ?", Long.class, accountId);
    String header = "交易编号-" + UUID.randomUUID().toString().substring(0, 8);
    String csv = header + ",交易日期,收支方向,交易金额\nALIAS-1,2026-09-09,收入,66.00\n";

    mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", "alias.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(44015));

    mockMvc.perform(post("/api/v1/bank-templates/mappings")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"header\":\"" + header + "\",\"field\":\"transaction_no\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(multipart("/api/v1/imports/bank-statements/preview")
            .file(new MockMultipartFile("file", "alias.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.success_rows").value(1))
        .andExpect(jsonPath("$.data.failed_rows").value(0));
  }

  @Test
  void templateManageRequiresPermission() throws Exception {
    String token = loginToken("biz01");
    mockMvc.perform(get("/api/v1/bank-templates").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
    mockMvc.perform(post("/api/v1/bank-templates/mappings")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"header\":\"x\",\"field\":\"summary\"}"))
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
