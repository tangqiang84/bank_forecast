package com.bankforecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

/** 应付计划导入和驾驶舱收付区块（应收 Top5/应付 Top5/资金缺口/风险项目）。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class PayableImportTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void importPayablesAndDedup() throws Exception {
    String token = loginToken("finance01");
    String marker = UUID.randomUUID().toString().substring(0, 8);
    String csv = "contract_no,supplier_name,node_name,due_date,plan_amount,paid_amount,status,remark\n"
        + ",供应商" + marker + "甲,服务器采购,2026-11-15,50000.00,,,\n"
        + ",供应商" + marker + "乙,外包服务费,2026-10-20,30000.00,10000.00,partial,季度外包\n";

    mockMvc.perform(multipart("/api/v1/imports/payables")
            .file(new MockMultipartFile("file", "payables.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(2));

    mockMvc.perform(multipart("/api/v1/imports/payables")
            .file(new MockMultipartFile("file", "payables.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success_rows").value(0))
        .andExpect(jsonPath("$.data.skipped_rows").value(2));

    mockMvc.perform(get("/api/v1/payables?status=partial")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].supplier_name").value("供应商" + marker + "乙"));
  }

  @Test
  void previewRetryConfirmFlow() throws Exception {
    String token = loginToken("finance01");
    String marker = UUID.randomUUID().toString().substring(0, 8);
    String csv = "contract_no,supplier_name,node_name,due_date,plan_amount\n"
        + ",预览供应商" + marker + ",硬件采购,2026-12-01,not-a-number\n"
        + ",预览供应商" + marker + ",软件采购,2026-12-05,20000.00\n";

    MvcResult preview = mockMvc.perform(multipart("/api/v1/imports/payables/preview")
            .file(new MockMultipartFile("file", "payables-preview.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.success_rows").value(1))
        .andExpect(jsonPath("$.data.failed_rows").value(1))
        .andReturn();
    String body = preview.getResponse().getContentAsString();
    String jobId = body.substring(body.indexOf("job_id") + 8, body.indexOf(',', body.indexOf("job_id")));

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/retry-errors")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rows\":[{\"row_no\":2,\"plan_amount\":\"15000.00\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.failed_rows").value(0))
        .andExpect(jsonPath("$.data.preview_rows[0].status").value("retry_success"));

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(2));
  }

  @Test
  void contractNoMustExistWhenProvided() throws Exception {
    String token = loginToken("finance01");
    String csv = "contract_no,supplier_name,node_name,due_date,plan_amount\n"
        + "NO-SUCH-CONTRACT,供应商丙,采购,2026-11-01,1000.00\n";

    mockMvc.perform(multipart("/api/v1/imports/payables/preview")
            .file(new MockMultipartFile("file", "payables-bad-contract.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.failed_rows").value(1))
        .andExpect(jsonPath("$.data.error_details[0].error_message").value(org.hamcrest.Matchers.containsString("合同不存在")));
  }

  @Test
  void payableImportRequiresPermission() throws Exception {
    String token = loginToken("biz01");
    String csv = "supplier_name,node_name,due_date,plan_amount\n供应商丁,采购,2026-11-01,1000.00\n";
    mockMvc.perform(multipart("/api/v1/imports/payables")
            .file(new MockMultipartFile("file", "payables.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void dashboardOverviewIncludesPayableBlocksAndFundingGap() throws Exception {
    String token = loginToken("finance01");
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    String marker = UUID.randomUUID().toString().substring(0, 8);
    String csv = "supplier_name,node_name,due_date,plan_amount\n"
        + "缺口供应商" + marker + ",近期付款,2026-10-25,40000.00\n";
    mockMvc.perform(multipart("/api/v1/imports/payables")
            .file(new MockMultipartFile("file", "payables-gap.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success_rows").value(1));

    MvcResult overview = mockMvc.perform(get("/api/v1/dashboard/overview")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.payable_top").isArray())
        .andExpect(jsonPath("$.data.payable_30d_amount").isNumber())
        .andExpect(jsonPath("$.data.funding_gap.balance").isNumber())
        .andExpect(jsonPath("$.data.funding_gap.has_gap").isBoolean())
        .andExpect(jsonPath("$.data.risk_projects").isArray())
        .andReturn();

    // 资金缺口口径：gap_amount = balance + receivable_30d - payable_30d
    String body = overview.getResponse().getContentAsString();
    com.fasterxml.jackson.databind.JsonNode gap = new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(body).path("data").path("funding_gap");
    BigDecimal expected = new BigDecimal(gap.path("balance").asText())
        .add(new BigDecimal(gap.path("receivable_30d").asText()))
        .subtract(new BigDecimal(gap.path("payable_30d").asText()));
    assertEquals(0, expected.compareTo(new BigDecimal(gap.path("gap_amount").asText())));
    assertTrue(gap.path("payable_30d").decimalValue().compareTo(new BigDecimal("40000.00")) >= 0);
    assertEquals(gap.path("gap_amount").decimalValue().compareTo(BigDecimal.ZERO) < 0,
        gap.path("has_gap").asBoolean());
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
