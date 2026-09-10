package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;

@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class AttachmentControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void uploadsAndDownloadsExceptionAttachment() throws Exception {
    String token = loginToken();
    Long tenantId = tenantId();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update("insert into exception_case (tenant_id, exception_no, exception_type, source_type, source_id, title, description) values (?, ?, 'unmatched', 'test', 1, '测试异常', '测试附件')",
        tenantId, "EX-" + suffix);
    Long exceptionId = jdbcTemplate.queryForObject("select id from exception_case where exception_no = ?", Long.class, "EX-" + suffix);
    byte[] payload = "附件内容".getBytes(StandardCharsets.UTF_8);

    mockMvc.perform(multipart("/api/v1/matching/exceptions/" + exceptionId + "/attachments")
            .file(new MockMultipartFile("file", "evidence.txt", "text/plain", payload))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.file_name").value("evidence.txt"));

    Long attachmentId = jdbcTemplate.queryForObject("select id from exception_attachment where exception_case_id = ?", Long.class, exceptionId);
    mockMvc.perform(get("/api/v1/matching/exceptions/attachments/" + attachmentId + "/download")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.TEXT_PLAIN))
        .andExpect(content().bytes(payload));

    mockMvc.perform(get("/api/v1/matching/exceptions/attachments/" + attachmentId + "/preview")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(content().bytes(payload));
    mockMvc.perform(delete("/api/v1/matching/exceptions/attachments/" + attachmentId)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
    mockMvc.perform(get("/api/v1/matching/exceptions/attachments/" + attachmentId + "/download")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(42001));
  }

  private Long tenantId() {
    return jdbcTemplate.queryForObject("select tenant_id from user_account where login_name = ?", Long.class, "finance01");
  }

  private String loginToken() throws Exception {
    MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"finance01\",\"password\":\"test-password-123\"}"))
        .andExpect(status().isOk()).andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
