package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "bank-forecast.bootstrap.default-admin-password=test-password-123",
    "spring.datasource.url=jdbc:h2:mem:exception_workbench_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExceptionWorkbenchTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeAll
  void seed() throws Exception {
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    Long financeUserId = jdbcTemplate.queryForObject(
        "select id from user_account where login_name = 'finance01'", Long.class);
    jdbcTemplate.update("insert into bank_transaction (tenant_id, bank_account_id, import_job_id, transaction_no, transaction_date, booking_date, direction, amount, match_status) "
        + "select ?, min(id), 0, 'TX-EXC-SEED', '2026-09-01', '2026-09-01', 'income', 100.00, 'unmatched' from bank_account where tenant_id = ?", tenantId, tenantId);
    Long transactionId = jdbcTemplate.queryForObject(
        "select id from bank_transaction where tenant_id = ? and transaction_no = 'TX-EXC-SEED'", Long.class, tenantId);
    insertException(tenantId, transactionId, "EX-WB-1", "unknown_receipt", "new", null, "2026-09-01");
    insertException(tenantId, transactionId, "EX-WB-2", "partial_receipt", "in_progress", financeUserId, "2026-09-01");
    insertException(tenantId, transactionId, "EX-WB-3", "overdue_receivable", "resolved", financeUserId, null);
    insertException(tenantId, transactionId, "EX-WB-4", "bank_unrecorded", "closed", financeUserId, null);
  }

  private void insertException(Long tenantId, Long sourceId, String exceptionNo, String type, String status,
      Long ownerUserId, String dueDate) {
    jdbcTemplate.update("insert into exception_case (tenant_id, exception_no, exception_type, source_type, source_id, title, description, owner_user_id, status, severity, due_date, closed_at) "
            + "values (?, ?, ?, 'bank_transaction', ?, ?, '工作台测试', ?, ?, 'medium', ?, ?)",
        tenantId, exceptionNo, type, sourceId, exceptionNo + " 标题", ownerUserId, status,
        dueDate == null ? null : java.sql.Date.valueOf(dueDate),
        "closed".equals(status) ? java.sql.Timestamp.valueOf("2026-09-20 10:00:00") : null);
  }

  @Test
  void statsAndQueues() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();

    mockMvc.perform(get("/api/v1/matching/exceptions/stats")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.active_count").value(2))
        .andExpect(jsonPath("$.data.unassigned_count").value(1))
        .andExpect(jsonPath("$.data.my_todo_count").value(1))
        .andExpect(jsonPath("$.data.pending_close_count").value(1))
        .andExpect(jsonPath("$.data.overdue_count").value(2))
        .andExpect(jsonPath("$.data.closed_count").value(1))
        .andExpect(jsonPath("$.data.total_count").value(4))
        .andExpect(jsonPath("$.data.by_owner[0].owner_name").value("财务负责人"))
        .andExpect(jsonPath("$.data.by_owner[0].active").value(1));

    mockMvc.perform(get("/api/v1/matching/exceptions?queue=mine")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].stage").value("处理中"))
        .andExpect(jsonPath("$.data.items[0].owner_name").value("财务负责人"));

    mockMvc.perform(get("/api/v1/matching/exceptions?queue=unassigned")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].stage").value("待分派"));

    mockMvc.perform(get("/api/v1/matching/exceptions?queue=pending_close")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].stage").value("待关闭"));

    Long financeUserId = jdbcTemplate.queryForObject(
        "select id from user_account where login_name = 'finance01'", Long.class);
    mockMvc.perform(get("/api/v1/matching/exceptions?owner_user_id=" + financeUserId)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(3));

    mockMvc.perform(get("/api/v1/matching/exceptions?queue=unknown_queue")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isBadRequest());
  }

  private Long tenantId() {
    return jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
  }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"test-password-123\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
