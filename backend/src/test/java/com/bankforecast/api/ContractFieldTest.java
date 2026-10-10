package com.bankforecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/** P3-7 合同责任人/收款账户字段补齐：导入解析、入库和详情返回。 */
@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class ContractFieldTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void importsOwnerAndReceiveAccount() throws Exception {
    String token = loginToken();
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    Long accountId = jdbcTemplate.queryForObject(
        "select min(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    String last4 = jdbcTemplate.queryForObject(
        "select account_no_last4 from bank_account where id = ?", String.class, accountId);
    String ownerName = jdbcTemplate.queryForObject(
        "select display_name from user_account where login_name = 'finance01' and tenant_id = ?",
        String.class, tenantId);
    String marker = UUID.randomUUID().toString().substring(0, 8);
    String contractNo = "CT-FIELD-" + marker;

    String csv = "contract_no,contract_name,customer_name,contract_amount,node_name,node_type,due_date,plan_amount,负责人,收款账户\n"
        + contractNo + ",字段验收合同,字段客户," + "9000.00,验收款,acceptance," + java.time.LocalDate.now().plusDays(10)
        + ",9000.00," + ownerName + "," + last4 + "\n";
    mockMvc.perform(multipart("/api/v1/imports/contracts")
            .file(new MockMultipartFile("file", "contracts-fields.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success_rows").value(1));

    Long contractId = jdbcTemplate.queryForObject(
        "select id from contract where tenant_id = ? and contract_no = ?", Long.class, tenantId, contractNo);
    assertEquals(accountId, jdbcTemplate.queryForObject(
        "select receive_account_id from contract where id = ?", Long.class, contractId));
    Long ownerId = jdbcTemplate.queryForObject(
        "select id from user_account where login_name = 'finance01' and tenant_id = ?", Long.class, tenantId);
    assertEquals(ownerId, jdbcTemplate.queryForObject(
        "select owner_user_id from contract_receivable_plan where contract_id = ?", Long.class, contractId));

    mockMvc.perform(get("/api/v1/contracts/" + contractId)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.contract.receive_account.account_no_last4").value(last4))
        .andExpect(jsonPath("$.data.receivables[0].owner_name").value(ownerName));
  }

  @Test
  void unknownOwnerAndAccountAreTolerated() throws Exception {
    String token = loginToken();
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    String marker = UUID.randomUUID().toString().substring(0, 8);
    String contractNo = "CT-FIELD-TOL-" + marker;
    String csv = "contract_no,contract_name,customer_name,contract_amount,node_name,node_type,due_date,plan_amount,负责人,收款账户\n"
        + contractNo + ",宽容合同,宽容客户,1000.00,验收款,acceptance," + java.time.LocalDate.now().plusDays(5)
        + ",1000.00,不存在的人,0000\n";
    mockMvc.perform(multipart("/api/v1/imports/contracts")
            .file(new MockMultipartFile("file", "contracts-tolerant.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success_rows").value(1));
    Long contractId = jdbcTemplate.queryForObject(
        "select id from contract where tenant_id = ? and contract_no = ?", Long.class, tenantId, contractNo);
    assertNotNull(contractId);
    org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject(
        "select receive_account_id from contract where id = ?", Long.class, contractId));
  }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"" + TEST_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
