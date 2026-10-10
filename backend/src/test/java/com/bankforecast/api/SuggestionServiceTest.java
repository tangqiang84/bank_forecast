package com.bankforecast.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestTemplate;

/** P3-5/P3-9 分类建议和异常解释草稿：规则降级与大模型路径（MockRestServiceServer 隔离）。 */
@SpringBootTest(properties = {
    "bank-forecast.bootstrap.default-admin-password=test-password-123",
    "bank-forecast.llm.enabled=true",
    "bank-forecast.llm.base-url=http://llm-mock.local/v1",
    "bank-forecast.llm.api-key=test-key",
    "bank-forecast.llm.model=test-model"})
@AutoConfigureMockMvc
class SuggestionServiceTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RestTemplate llmRestTemplate;

  private MockRestServiceServer llmServer;

  @BeforeEach
  void setUp() {
    llmServer = MockRestServiceServer.bindTo(llmRestTemplate).ignoreExpectOrder(true).build();
  }

  @Test
  void suggestsCategoryFromLlmWithFallbackToRule() throws Exception {
    String token = loginToken();
    Long txId = importTransaction(token, "SC-" + UUID.randomUUID().toString().substring(0, 8), "技术咨询服务费");

    // 第一次大模型返回合法类别 → 采用；第二次返回非法类别 → 降级规则
    llmServer.expect(requestTo(containsString("/chat/completions")))
        .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"服务费\"}}]}", MediaType.APPLICATION_JSON));
    llmServer.expect(requestTo(containsString("/chat/completions")))
        .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"胡言乱语\"}}]}", MediaType.APPLICATION_JSON));

    mockMvc.perform(post("/api/v1/bank-transactions/" + txId + "/suggest-category")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.suggested_category").value("服务费"))
        .andExpect(jsonPath("$.data.source").value("llm"));

    mockMvc.perform(post("/api/v1/bank-transactions/" + txId + "/suggest-category")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.suggested_category").value("服务费"))
        .andExpect(jsonPath("$.data.source").value("rule"));
  }

  @Test
  void exceptionExplainDraft() throws Exception {
    String token = loginToken();
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class);
    jdbcTemplate.update(
        "insert into exception_case (tenant_id, exception_no, exception_type, source_type, source_id, title, description, status, severity) values (?, ?, 'unknown_receipt', 'bank_transaction', 0, '未知收款测试', '到账 500 元无合同匹配', 'new', 'medium')",
        tenantId, "EXC-DRAFT-" + UUID.randomUUID().toString().replace("-", ""));
    Long exceptionId = jdbcTemplate.queryForObject(
        "select max(id) from exception_case where tenant_id = ? and exception_type = 'unknown_receipt'",
        Long.class, tenantId);

    llmServer.expect(requestTo(containsString("/chat/completions")))
        .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"该笔到账无对应合同，可能为客户预付款。\"}}]}", MediaType.APPLICATION_JSON));
    mockMvc.perform(post("/api/v1/matching/exceptions/" + exceptionId + "/explain-draft")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.source").value("llm"))
        .andExpect(jsonPath("$.data.draft").value(containsString("预付款")));
  }

  private Long importTransaction(String token, String transactionNo, String summary) throws Exception {
    Long accountId = jdbcTemplate.queryForObject("select min(id) from bank_account", Long.class);
    Long tenantId = jdbcTemplate.queryForObject(
        "select tenant_id from bank_account where id = ?", Long.class, accountId);
    String csv = "transaction_no,transaction_date,direction,amount,counterparty_name,summary\n"
        + transactionNo + ",2026-10-10,income,500.00,建议测试客户," + summary + "\n";
    mockMvc.perform(multipart("/api/v1/imports/bank-statements")
            .file(new MockMultipartFile("file", "suggest.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("bank_account_id", String.valueOf(accountId))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    return jdbcTemplate.queryForObject(
        "select id from bank_transaction where tenant_id = ? and transaction_no = ?",
        Long.class, tenantId, transactionNo);
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
