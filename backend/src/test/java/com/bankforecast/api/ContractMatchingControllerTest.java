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
class ContractMatchingControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void importsContractsMatchesReceiptAndCreatesUnknownException() throws Exception {
    String token = loginToken();
    Long tenantId = jdbcTemplate.queryForObject("select tenant_id from user_account where login_name = ?", Long.class, "finance01");
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String contractNo = "CT-" + suffix;
    String transactionNo = "TX-" + suffix;
    String csv = "contract_no,contract_name,customer_name,project_no,project_name,contract_amount,node_name,node_type,due_date,plan_amount,owner_name\n"
        + contractNo + ",软件实施合同,甲方科技,PRJ-" + suffix + ",甲方一期项目,1000.00,验收款,acceptance,2026-09-01,1000.00,财务负责人\n";
    MockMultipartFile file = new MockMultipartFile("file", "contracts.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

    mockMvc.perform(multipart("/api/v1/imports/contracts").file(file)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(1));

    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    String statement = "transaction_no,transaction_date,direction,amount,balance_after,counterparty_name,summary\n"
        + transactionNo + ",2026-09-01,income,1000.00,2866300.00,甲方科技,合同 " + contractNo + " 验收款\n";
    MockMultipartFile statementFile = new MockMultipartFile("file", "statement.csv", "text/csv", statement.getBytes(StandardCharsets.UTF_8));
    mockMvc.perform(multipart("/api/v1/imports/bank-statements").file(statementFile)
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/v1/matching/receivables/run")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.matched").value(1));

    mockMvc.perform(get("/api/v1/contracts/receivables")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    org.junit.jupiter.api.Assertions.assertEquals("paid", jdbcTemplate.queryForObject(
        "select p.status from contract_receivable_plan p join contract c on c.id = p.contract_id where c.contract_no = ?",
        String.class, contractNo));

    mockMvc.perform(get("/api/v1/matching/exceptions")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
  }

  @Test
  void partialReceiptAndOverduePlanCreateExceptions() throws Exception {
    String token = loginToken();
    Long tenantId = jdbcTemplate.queryForObject("select tenant_id from user_account where login_name = ?", Long.class, "finance01");
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String contractNo = "CT-P-" + suffix;
    String csv = "contract_no,contract_name,customer_name,contract_amount,node_name,node_type,due_date,plan_amount\n"
        + contractNo + ",部分收款合同,乙方科技,2000.00,首付款,milestone,2020-01-01,2000.00\n";
    MockMultipartFile file = new MockMultipartFile("file", "contracts.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    mockMvc.perform(multipart("/api/v1/imports/contracts").file(file)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    String statement = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + "TX-P-" + suffix + ",2020-01-01,income,500.00,乙方科技,首付款\n";
    MockMultipartFile statementFile = new MockMultipartFile("file", "statement.csv", "text/csv", statement.getBytes(StandardCharsets.UTF_8));
    mockMvc.perform(multipart("/api/v1/imports/bank-statements").file(statementFile)
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/v1/matching/receivables/run")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.suggested").value(1));
    Long planId = jdbcTemplate.queryForObject("select p.id from contract_receivable_plan p join contract c on c.id = p.contract_id where c.contract_no = ?", Long.class, contractNo);
    org.junit.jupiter.api.Assertions.assertEquals("0.00", jdbcTemplate.queryForObject("select paid_amount from contract_receivable_plan where id = ?", String.class, planId));
    org.junit.jupiter.api.Assertions.assertEquals("unpaid", jdbcTemplate.queryForObject("select status from contract_receivable_plan where id = ?", String.class, planId));
    mockMvc.perform(get("/api/v1/matching/exceptions")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].exception_type").exists());
  }

  @Test
  void confirmsSuggestedResultAndAppliesFinancialEffect() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Long resultId = createPartialMatch(tenantId, suffix);

    mockMvc.perform(post("/api/v1/matching/results/" + resultId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.match_status").value("confirmed"))
        .andExpect(jsonPath("$.data.transaction_match_status").value("manual_confirmed"));

    Long planId = jdbcTemplate.queryForObject("select contract_receivable_plan_id from match_result where id = ?", Long.class, resultId);
    org.junit.jupiter.api.Assertions.assertEquals("500.00", jdbcTemplate.queryForObject("select paid_amount from contract_receivable_plan where id = ?", String.class, planId));
    org.junit.jupiter.api.Assertions.assertEquals("partial", jdbcTemplate.queryForObject("select status from contract_receivable_plan where id = ?", String.class, planId));
    org.junit.jupiter.api.Assertions.assertNotNull(jdbcTemplate.queryForObject("select confirmed_by from match_result where id = ?", Long.class, resultId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("select count(*) from exception_action_log where exception_case_id in (select id from exception_case where source_id = ? and exception_type = 'partial_receipt') and action_type = 'CONFIRM'", Integer.class, planId).intValue());
  }

  @Test
  void rejectsSuggestedResultWithoutApplyingFinancialEffectAndPreventsRepeatAction() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Long resultId = createPartialMatch(tenantId, suffix);

    mockMvc.perform(post("/api/v1/matching/results/" + resultId + "/reject")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"客户名称不一致\"}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.match_status").value("rejected"))
        .andExpect(jsonPath("$.data.transaction_match_status").value("unmatched"));

    Long transactionId = jdbcTemplate.queryForObject("select bank_transaction_id from match_result where id = ?", Long.class, resultId);
    Long planId = jdbcTemplate.queryForObject("select contract_receivable_plan_id from match_result where id = ?", Long.class, resultId);
    org.junit.jupiter.api.Assertions.assertEquals("unmatched", jdbcTemplate.queryForObject("select match_status from bank_transaction where id = ?", String.class, transactionId));
    org.junit.jupiter.api.Assertions.assertEquals("0.00", jdbcTemplate.queryForObject("select paid_amount from contract_receivable_plan where id = ?", String.class, planId));
    org.junit.jupiter.api.Assertions.assertEquals("resolved", jdbcTemplate.queryForObject("select status from exception_case where source_id = ? and exception_type = 'partial_receipt'", String.class, planId));
    mockMvc.perform(post("/api/v1/matching/results/" + resultId + "/reject")
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"重复操作\"}")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(43002));
  }

  @Test
  void cannotOperateMissingOrOtherTenantResult() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    mockMvc.perform(post("/api/v1/matching/results/999999999/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(42001));
    mockMvc.perform(post("/api/v1/matching/results/999999999/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", "999999"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(41003));
  }

  private Long createPartialMatch(Long tenantId, String suffix) throws Exception {
    String token = loginToken();
    String contractNo = "CT-A-" + suffix;
    String transactionNo = "TX-A-" + suffix;
    String customerName = "丙方科技" + suffix;
    String csv = "contract_no,contract_name,customer_name,contract_amount,node_name,node_type,due_date,plan_amount\n"
        + contractNo + ",人工确认合同," + customerName + ",2000.00,首付款,milestone,2020-01-01,2000.00\n";
    mockMvc.perform(multipart("/api/v1/imports/contracts")
            .file(new MockMultipartFile("file", "contracts.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    String statement = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + transactionNo + ",2020-01-01,income,500.00," + customerName + ",首付款\n";
    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "statement.csv", "text/csv", statement.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/v1/matching/receivables/run")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.suggested").value(1));
    return jdbcTemplate.queryForObject("select mr.id from match_result mr join bank_transaction bt on bt.id = mr.bank_transaction_id where bt.transaction_no = ?", Long.class, transactionNo);
  }

  private Long tenantId() {
    return jdbcTemplate.queryForObject("select tenant_id from user_account where login_name = ?", Long.class, "finance01");
  }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"test-password-123\"}"))
        .andExpect(status().isOk()).andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
