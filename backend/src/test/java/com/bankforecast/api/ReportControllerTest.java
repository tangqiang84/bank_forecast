package com.bankforecast.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "bank-forecast.bootstrap.default-admin-password=test-password-123",
    "spring.datasource.url=jdbc:h2:mem:report_controller_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ReportControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void generatesMonthlyAndHealthReportsAndDownloadsCsv() throws Exception {
    String token = loginToken();
    Long tenantId = jdbcTemplate.queryForObject("select tenant_id from user_account where login_name = ?", Long.class, "finance01");
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String csv = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + "RPT-IN-" + suffix + ",2026-09-09,income,100.00,报表客户,项目回款\n"
        + "RPT-OUT-" + suffix + ",2026-09-10,expense,30.00,报表供应商,采购付款\n";
    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "report.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.success_rows").value(2));

    MvcResult monthly = mockMvc.perform(post("/api/v1/reports")
            .contentType("application/json")
            .content("{\"report_type\":\"monthly\",\"params_json\":{\"month\":\"2026-09\"}}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.result.income_total").value(100.00))
        .andExpect(jsonPath("$.data.result.expense_total").value(30.00)).andReturn();
    Long monthlyId = reportId(monthly);

    mockMvc.perform(post("/api/v1/reports")
            .contentType("application/json")
            .content("{\"report_type\":\"health\",\"params_json\":{}}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.result.health_score").isNumber())
        .andExpect(jsonPath("$.data.result.health_level").value("healthy"));

    mockMvc.perform(get("/api/v1/reports").header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
        .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.page_size").value(20))
        .andExpect(jsonPath("$.data.total").value(2));

    mockMvc.perform(get("/api/v1/reports/" + monthlyId + "/download")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", containsString("report-" + monthlyId + ".csv")))
        .andExpect(content().contentTypeCompatibleWith("text/csv"))
        .andExpect(content().string(containsString("income_total")))
        .andExpect(content().string(containsString("100.00")));
  }

  @Test
  void generatesWeeklyReportAndExportsXlsxAndPrintHtml() throws Exception {
    String token = loginToken();
    MvcResult weekly = mockMvc.perform(post("/api/v1/reports")
            .contentType("application/json")
            .content("{\"report_type\":\"weekly\",\"params_json\":{\"week\":\"2026-09-10\"}}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantIdHeader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.report_type").value("weekly"))
        .andExpect(jsonPath("$.data.date_from").value("2026-09-07"))
        .andExpect(jsonPath("$.data.date_to").value("2026-09-13"))
        .andExpect(jsonPath("$.data.result.report_name").value("资金周报"))
        .andExpect(jsonPath("$.data.result.receivable_due_in_week").exists())
        .andExpect(jsonPath("$.data.result.confirmed_receipts_in_week").exists())
        .andExpect(jsonPath("$.data.result.active_accounts_in_week").exists())
        .andExpect(jsonPath("$.data.result.exceptions_closed_in_week").exists())
        .andReturn();
    Long weeklyId = reportId(weekly);

    mockMvc.perform(get("/api/v1/reports/" + weeklyId + "/download?format=xlsx")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantIdHeader()))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
        .andExpect(content().contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(result -> {
          byte[] body = result.getResponse().getContentAsByteArray();
          org.junit.jupiter.api.Assertions.assertEquals('P', body[0]);
          org.junit.jupiter.api.Assertions.assertEquals('K', body[1]);
        });

    mockMvc.perform(get("/api/v1/reports/" + weeklyId + "/print")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantIdHeader()))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        .andExpect(content().string(containsString("资金周报")))
        .andExpect(content().string(containsString("指标概览")))
        .andExpect(content().string(containsString("2026-09-07")));
  }

  private String tenantIdHeader() {
    return String.valueOf(jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = ?", Long.class, "finance01"));
  }

  private Long reportId(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    int start = body.indexOf("\"id\":") + 5;
    return Long.valueOf(body.substring(start, body.indexOf(',', start)));
  }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content("{\"login_name\":\"finance01\",\"password\":\"test-password-123\"}"))
        .andExpect(status().isOk()).andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
