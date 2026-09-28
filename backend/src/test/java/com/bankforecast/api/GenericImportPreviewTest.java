package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
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
class GenericImportPreviewTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void contractPreviewConfirmWithSkippedRow() throws Exception {
    String token = loginToken("finance01");
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String contractNo = "CT-PRE-" + suffix;
    String csv = "contract_no,customer_name,contract_name,contract_amount,node_name,node_type,due_date,plan_amount\n"
        + contractNo + ",预览客户甲,预览合同甲,1000.00,首期,receivable,2026-10-01,500.00\n"
        + contractNo + ",预览客户甲,预览合同甲,1000.00,首期,receivable,2026-10-01,500.00\n";

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/contracts/preview")
            .file(new MockMultipartFile("file", "contracts-preview.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.job_type").value("contract"))
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.success_rows").value(2))
        .andExpect(jsonPath("$.data.preview_rows[0].status").value("valid"))
        .andExpect(jsonPath("$.data.preview_rows[0].payload.contract_no").value(contractNo))
        .andReturn();
    Assertions.assertEquals(0, countPlans(contractNo));
    String jobId = extractJobId(result.getResponse().getContentAsString());

    mockMvc.perform(get("/api/v1/imports/" + jobId + "/preview")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.job_type").value("contract"))
        .andExpect(jsonPath("$.data.preview_rows[1].payload.plan_amount").value("500.00"));

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(1))
        .andExpect(jsonPath("$.data.skipped_rows").value(1));
    Assertions.assertEquals(1, countPlans(contractNo));
  }

  @Test
  void contractPreviewRetryThenConfirm() throws Exception {
    String token = loginToken("finance01");
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String contractNo = "CT-RETRY-" + suffix;
    String csv = "contract_no,customer_name,contract_name,contract_amount,node_name,node_type,due_date,plan_amount\n"
        + contractNo + ",预览客户乙,预览合同乙,1000.00,首期,receivable,2026-10-01,500.00\n"
        + contractNo + ",预览客户乙,预览合同乙,1000.00,二期,receivable,2026-11-01,1500.00\n";

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/contracts/preview")
            .file(new MockMultipartFile("file", "contracts-retry.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.failed_rows").value(1))
        .andExpect(jsonPath("$.data.preview_rows[1].status").value("failed"))
        .andReturn();
    String jobId = extractJobId(result.getResponse().getContentAsString());

    String retry = "{\"rows\":[{\"row_no\":3,\"plan_amount\":\"400.00\"}]}";
    mockMvc.perform(post("/api/v1/imports/" + jobId + "/retry-errors")
            .contentType(MediaType.APPLICATION_JSON).content(retry)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.failed_rows").value(0))
        .andExpect(jsonPath("$.data.preview_rows[1].status").value("retry_success"));

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(2));
    Assertions.assertEquals(2, countPlans(contractNo));
  }

  @Test
  void financePreviewConfirmWithSkippedRow() throws Exception {
    String token = loginToken("finance01");
    String recordNo = "FR-PRE-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "record_no,record_type,record_date,amount,counterparty_name,source_system,summary\n"
        + recordNo + ",receipt,2026-09-09,100.00,供应商甲,ERP,货款\n"
        + recordNo + ",receipt,2026-09-09,100.00,供应商甲,ERP,货款\n";

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/finance-records/preview")
            .file(new MockMultipartFile("file", "finance-preview.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.job_type").value("finance_record"))
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.success_rows").value(2))
        .andExpect(jsonPath("$.data.preview_rows[0].payload.record_no").value(recordNo))
        .andReturn();
    Assertions.assertEquals(0, countFinanceRecords(recordNo));
    String jobId = extractJobId(result.getResponse().getContentAsString());

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(1))
        .andExpect(jsonPath("$.data.skipped_rows").value(1));
    Assertions.assertEquals(1, countFinanceRecords(recordNo));
  }

  @Test
  void financePreviewRetryThenConfirm() throws Exception {
    String token = loginToken("finance01");
    String recordNo = "FR-RETRY-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "record_no,record_type,record_date,amount,counterparty_name,source_system,summary\n"
        + recordNo + "-A,receipt,2026-09-09,100.00,供应商乙,ERP,货款\n"
        + recordNo + "-B,payment,2026-09-10,10.123,供应商乙,ERP,退款\n";

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/finance-records/preview")
            .file(new MockMultipartFile("file", "finance-retry.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.failed_rows").value(1))
        .andExpect(jsonPath("$.data.preview_rows[1].status").value("failed"))
        .andReturn();
    String jobId = extractJobId(result.getResponse().getContentAsString());

    String retry = "{\"rows\":[{\"row_no\":3,\"amount\":\"10.12\"}]}";
    mockMvc.perform(post("/api/v1/imports/" + jobId + "/retry-errors")
            .contentType(MediaType.APPLICATION_JSON).content(retry)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.failed_rows").value(0))
        .andExpect(jsonPath("$.data.preview_rows[1].status").value("retry_success"));

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(2));
    Assertions.assertEquals(1, countFinanceRecords(recordNo + "-B"));
  }

  @Test
  void cashierCannotConfirmContractPreviewJob() throws Exception {
    String financeToken = loginToken("finance01");
    String contractNo = "CT-AUTH-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "contract_no,customer_name,contract_name,contract_amount\n"
        + contractNo + ",越权客户,越权合同,100.00\n";
    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/contracts/preview")
            .file(new MockMultipartFile("file", "contracts-auth.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + financeToken).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andReturn();
    String jobId = extractJobId(result.getResponse().getContentAsString());

    String cashierToken = loginToken("cashier01");
    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + cashierToken).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void confirmRejectsNonPreviewStateAndRepeatConfirmDoesNotDuplicate() throws Exception {
    String token = loginToken("finance01");
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String contractNo = "CT-STATE-" + suffix;
    String csv = "contract_no,customer_name,contract_name,contract_amount\n"
        + contractNo + ",状态客户,状态合同,100.00\n";
    MockMultipartFile file = new MockMultipartFile("file", "contracts-state.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

    MvcResult direct = mockMvc.perform(multipart("/api/v1/imports/contracts").file(file)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andReturn();
    String directJobId = extractJobId(direct.getResponse().getContentAsString());
    mockMvc.perform(post("/api/v1/imports/" + directJobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(44015));

    String previewContractNo = "CT-STATE2-" + suffix;
    String previewCsv = "contract_no,customer_name,contract_name,contract_amount,node_name,node_type,due_date,plan_amount\n"
        + previewContractNo + ",状态客户,状态合同二,100.00,首期,receivable,2026-10-01,60.00\n";
    MvcResult preview = mockMvc.perform(multipart("/api/v1/imports/contracts/preview")
            .file(new MockMultipartFile("file", "contracts-state2.csv", "text/csv", previewCsv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andReturn();
    String previewJobId = extractJobId(preview.getResponse().getContentAsString());
    mockMvc.perform(post("/api/v1/imports/" + previewJobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"));
    Assertions.assertEquals(1, countPlans(previewContractNo));
    mockMvc.perform(post("/api/v1/imports/" + previewJobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(44015));
    Assertions.assertEquals(1, countPlans(previewContractNo));
  }

  @Test
  void projectPreviewConfirmWithSkippedRow() throws Exception {
    String token = loginToken("finance01");
    String projectNo = "PJ-PRE-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "project_no,project_name,customer_name,project_manager,project_status,start_date,delivery_date,acceptance_date,remark\n"
        + projectNo + ",预览项目甲,客户甲,李四,active,2026-09-01,2026-11-15,2026-11-30,重点项目\n"
        + projectNo + ",预览项目甲,客户甲,李四,active,2026-09-01,2026-11-15,2026-11-30,重点项目\n";

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/projects/preview")
            .file(new MockMultipartFile("file", "projects-preview.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.job_type").value("project"))
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.success_rows").value(2))
        .andExpect(jsonPath("$.data.preview_rows[0].status").value("valid"))
        .andExpect(jsonPath("$.data.preview_rows[0].payload.project_no").value(projectNo))
        .andReturn();
    Assertions.assertEquals(0, countProjects(projectNo));
    String jobId = extractJobId(result.getResponse().getContentAsString());

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(1))
        .andExpect(jsonPath("$.data.skipped_rows").value(1));
    Assertions.assertEquals(1, countProjects(projectNo));
    Map<String, Object> project = jdbcTemplate.queryForMap(
        "select project_manager, project_status, start_date, delivery_date, acceptance_date, remark from project where project_no = ?", projectNo);
    Assertions.assertEquals("李四", project.get("project_manager"));
    Assertions.assertEquals("2026-09-01", String.valueOf(project.get("start_date")));
    Assertions.assertEquals("重点项目", project.get("remark"));
  }

  @Test
  void projectPreviewRetryThenConfirm() throws Exception {
    String token = loginToken("finance01");
    String projectNo = "PJ-RETRY-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "project_no,project_name,project_status,start_date\n"
        + projectNo + "-A,重试项目甲,active,2026-09-01\n"
        + projectNo + "-B,重试项目乙,unknown,2026-09-01\n";

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/projects/preview")
            .file(new MockMultipartFile("file", "projects-retry.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.failed_rows").value(1))
        .andExpect(jsonPath("$.data.preview_rows[1].status").value("failed"))
        .andReturn();
    String jobId = extractJobId(result.getResponse().getContentAsString());

    String retry = "{\"rows\":[{\"row_no\":3,\"project_status\":\"paused\"}]}";
    mockMvc.perform(post("/api/v1/imports/" + jobId + "/retry-errors")
            .contentType(MediaType.APPLICATION_JSON).content(retry)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.failed_rows").value(0))
        .andExpect(jsonPath("$.data.preview_rows[1].status").value("retry_success"));

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(2));
    Assertions.assertEquals(1, countProjects(projectNo + "-B"));
  }

  @Test
  void projectDirectImportAndPermissionDenied() throws Exception {
    String token = loginToken("finance01");
    String projectNo = "PJ-DIRECT-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "项目编号,项目名称,客户名称,项目负责人\n"
        + projectNo + ",直导项目,客户丙,王五\n";
    mockMvc.perform(multipart("/api/v1/imports/projects")
            .file(new MockMultipartFile("file", "projects-direct.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(1));
    Assertions.assertEquals(1, countProjects(projectNo));

    String cashierToken = loginToken("cashier01");
    mockMvc.perform(multipart("/api/v1/imports/projects/preview")
            .file(new MockMultipartFile("file", "projects-auth.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + cashierToken).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void cashierCannotConfirmProjectPreviewJob() throws Exception {
    String financeToken = loginToken("finance01");
    String projectNo = "PJ-AUTH-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "project_no,project_name\n" + projectNo + ",越权项目\n";
    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/projects/preview")
            .file(new MockMultipartFile("file", "projects-confirm-auth.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + financeToken).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andReturn();
    String jobId = extractJobId(result.getResponse().getContentAsString());

    String cashierToken = loginToken("cashier01");
    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + cashierToken).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void receiptPreviewConfirmWithSkippedRow() throws Exception {
    String token = loginToken("finance01");
    String receiptNo = "RC-PRE-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "receipt_no,transaction_date,amount,payer_name,payee_name,summary\n"
        + receiptNo + ",2026-09-20,1000.00,客户甲,演示企业,货款\n"
        + receiptNo + ",2026-09-20,1000.00,客户甲,演示企业,货款\n";

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/receipts/preview")
            .file(new MockMultipartFile("file", "receipts-preview.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.job_type").value("receipt"))
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.success_rows").value(2))
        .andExpect(jsonPath("$.data.preview_rows[0].payload.receipt_no").value(receiptNo))
        .andReturn();
    Assertions.assertEquals(0, countReceipts(receiptNo));
    String jobId = extractJobId(result.getResponse().getContentAsString());

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(1))
        .andExpect(jsonPath("$.data.skipped_rows").value(1));
    Assertions.assertEquals(1, countReceipts(receiptNo));
  }

  @Test
  void receiptPreviewRetryThenConfirm() throws Exception {
    String token = loginToken("finance01");
    String receiptNo = "RC-RETRY-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "receipt_no,transaction_date,amount\n"
        + receiptNo + "-A,2026-09-20,100.00\n"
        + receiptNo + "-B,2026-09-21,abc\n";

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/receipts/preview")
            .file(new MockMultipartFile("file", "receipts-retry.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andExpect(jsonPath("$.data.failed_rows").value(1))
        .andExpect(jsonPath("$.data.preview_rows[1].status").value("failed"))
        .andReturn();
    String jobId = extractJobId(result.getResponse().getContentAsString());

    String retry = "{\"rows\":[{\"row_no\":3,\"amount\":\"200.00\"}]}";
    mockMvc.perform(post("/api/v1/imports/" + jobId + "/retry-errors")
            .contentType(MediaType.APPLICATION_JSON).content(retry)
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.failed_rows").value(0))
        .andExpect(jsonPath("$.data.preview_rows[1].status").value("retry_success"));

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("success"))
        .andExpect(jsonPath("$.data.success_rows").value(2));
    Assertions.assertEquals(1, countReceipts(receiptNo + "-B"));
  }

  @Test
  void receiptConfirmFailsWhenTransactionNoNotMatched() throws Exception {
    String token = loginToken("finance01");
    String receiptNo = "RC-LINK-" + UUID.randomUUID().toString().replace("-", "");
    String csv = "receipt_no,transaction_date,amount,transaction_no\n"
        + receiptNo + ",2026-09-20,100.00,TX-NOT-EXIST-000\n";

    MvcResult result = mockMvc.perform(multipart("/api/v1/imports/receipts/preview")
            .file(new MockMultipartFile("file", "receipts-link.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("preview_pending"))
        .andReturn();
    String jobId = extractJobId(result.getResponse().getContentAsString());

    mockMvc.perform(post("/api/v1/imports/" + jobId + "/confirm")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("failed"))
        .andExpect(jsonPath("$.data.success_rows").value(0));
    Assertions.assertEquals(0, countReceipts(receiptNo));
  }

  @Test
  void ceoCannotPreviewReceiptImport() throws Exception {
    String ceoToken = loginToken("ceo01");
    String csv = "receipt_no,transaction_date,amount\nRC-AUTH-1,2026-09-20,100.00\n";
    mockMvc.perform(multipart("/api/v1/imports/receipts/preview")
            .file(new MockMultipartFile("file", "receipts-auth.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + ceoToken).header("X-Tenant-Id", tenantId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  private int countPlans(String contractNo) {
    return jdbcTemplate.queryForObject(
        "select count(*) from contract_receivable_plan p join contract c on c.id = p.contract_id where c.contract_no = ?",
        Integer.class, contractNo);
  }

  private int countFinanceRecords(String recordNo) {
    return jdbcTemplate.queryForObject(
        "select count(*) from finance_record where record_no = ?", Integer.class, recordNo);
  }

  private int countProjects(String projectNo) {
    return jdbcTemplate.queryForObject(
        "select count(*) from project where project_no = ?", Integer.class, projectNo);
  }

  private int countReceipts(String receiptNo) {
    return jdbcTemplate.queryForObject(
        "select count(*) from receipt where receipt_no = ?", Integer.class, receiptNo);
  }

  private String extractJobId(String body) {
    return body.substring(body.indexOf("job_id") + 8, body.indexOf(',', body.indexOf("job_id")));
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
