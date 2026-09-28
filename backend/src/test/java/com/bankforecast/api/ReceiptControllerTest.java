package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
class ReceiptControllerTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void uploadImageAndPreview() throws Exception {
    String token = loginToken("finance01");
    String receiptNo = "RC-IMG-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "receipt_no,transaction_date,amount\n" + receiptNo + ",2026-09-20,100.00\n";
    mockMvc.perform(multipart("/api/v1/imports/receipts")
            .file(new MockMultipartFile("file", "receipts.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"));
    Long receiptId = jdbcTemplate.queryForObject(
        "select id from receipt where receipt_no = ?", Long.class, receiptNo);

    byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    mockMvc.perform(multipart("/api/v1/receipts/" + receiptId + "/image")
            .file(new MockMultipartFile("file", "receipt.png", "image/png", png))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.image_file_name").value("receipt.png"))
        .andExpect(jsonPath("$.data.has_image").value(true));

    mockMvc.perform(get("/api/v1/receipts/" + receiptId + "/image/preview")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.IMAGE_PNG))
        .andExpect(content().bytes(png));

    mockMvc.perform(get("/api/v1/receipts?page=1&page_size=20&receipt_no=" + receiptNo)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].has_image").value(true));
  }

  @Test
  void ceoCannotListReceiptsOrUploadImage() throws Exception {
    String ceoToken = loginToken("ceo01");
    mockMvc.perform(get("/api/v1/receipts?page=1&page_size=20")
            .header("Authorization", "Bearer " + ceoToken).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void rejectUnsupportedImageType() throws Exception {
    String token = loginToken("finance01");
    String receiptNo = "RC-TYPE-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "receipt_no,transaction_date,amount\n" + receiptNo + ",2026-09-20,100.00\n";
    mockMvc.perform(multipart("/api/v1/imports/receipts")
            .file(new MockMultipartFile("file", "receipts.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk());
    Long receiptId = jdbcTemplate.queryForObject(
        "select id from receipt where receipt_no = ?", Long.class, receiptNo);

    mockMvc.perform(multipart("/api/v1/receipts/" + receiptId + "/image")
            .file(new MockMultipartFile("file", "evil.exe", "application/octet-stream", new byte[] {1, 2, 3}))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isBadRequest());
  }

  private String tenantId() {
    return String.valueOf(jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class));
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
