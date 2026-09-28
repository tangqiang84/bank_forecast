package com.bankforecast.api;

import com.bankforecast.security.RequirePermission;
import com.bankforecast.common.ApiResponse;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.contract.ContractImportService;
import com.bankforecast.importjob.ImportJobService;
import com.bankforecast.finance.FinanceRecordImportService;
import java.util.Map;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

  private final ImportJobService importJobService;
  private final ContractImportService contractImportService;
  private final FinanceRecordImportService financeRecordImportService;

  public ImportController(ImportJobService importJobService, ContractImportService contractImportService,
      FinanceRecordImportService financeRecordImportService) {
    this.importJobService = importJobService;
    this.contractImportService = contractImportService;
    this.financeRecordImportService = financeRecordImportService;
  }

  @RequirePermission("contract:import")
  @PostMapping("/contracts")
  public ApiResponse<Map<String, Object>> importContracts(@RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(contractImportService.importContracts(file));
  }

  @RequirePermission("contract:import")
  @PostMapping("/contracts/preview")
  public ApiResponse<Map<String, Object>> previewContracts(@RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(contractImportService.previewContracts(file));
  }

  @RequirePermission("transaction:import")
  @PostMapping("/bank-statements")
  public ApiResponse<Map<String, Object>> importBankStatements(
      @RequestParam("bank_account_id") Long bankAccountId,
      @RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(importJobService.importBankStatements(bankAccountId, file));
  }

  @RequirePermission("transaction:import")
  @PostMapping("/bank-statements/preview")
  public ApiResponse<Map<String, Object>> previewBankStatements(
      @RequestParam("bank_account_id") Long bankAccountId,
      @RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(importJobService.previewBankStatements(bankAccountId, file));
  }

  @RequirePermission("import:view")
  @GetMapping("/{jobId}/preview")
  public ApiResponse<Map<String, Object>> preview(@PathVariable Long jobId) {
    String jobType = importJobService.getJobType(jobId);
    if ("contract".equals(jobType)) {
      return ApiResponse.ok(contractImportService.getPreview(jobId));
    }
    if ("finance_record".equals(jobType)) {
      return ApiResponse.ok(financeRecordImportService.getPreview(jobId));
    }
    return ApiResponse.ok(importJobService.getPreview(jobId));
  }

  @RequirePermission({"transaction:import", "contract:import", "reconciliation:run"})
  @PostMapping("/{jobId}/confirm")
  public ApiResponse<Map<String, Object>> confirm(@PathVariable Long jobId) {
    String jobType = importJobService.getJobType(jobId);
    if ("contract".equals(jobType)) {
      return ApiResponse.ok(contractImportService.confirmPreview(jobId));
    }
    if ("finance_record".equals(jobType)) {
      return ApiResponse.ok(financeRecordImportService.confirmPreview(jobId));
    }
    if ("bank_statement".equals(jobType)) {
      return ApiResponse.ok(importJobService.confirmPreview(jobId));
    }
    throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "该导入任务类型不支持确认");
  }

  @RequirePermission({"transaction:import", "contract:import", "reconciliation:run"})
  @PostMapping("/{jobId}/retry-errors")
  public ApiResponse<Map<String, Object>> retryErrors(@PathVariable Long jobId, @RequestBody Map<String, Object> request) {
    String jobType = importJobService.getJobType(jobId);
    if ("contract".equals(jobType)) {
      return ApiResponse.ok(contractImportService.retryPreviewErrors(jobId, request));
    }
    if ("finance_record".equals(jobType)) {
      return ApiResponse.ok(financeRecordImportService.retryPreviewErrors(jobId, request));
    }
    if ("bank_statement".equals(jobType)) {
      return ApiResponse.ok(importJobService.retryPreviewErrors(jobId, request));
    }
    throw new BusinessException(ErrorCode.ROW_DATA_ERROR, "该导入任务类型不支持失败行重试");
  }

  @RequirePermission("reconciliation:run")
  @PostMapping("/finance-records")
  public ApiResponse<Map<String, Object>> importFinanceRecords(@RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(financeRecordImportService.importRecords(file));
  }

  @RequirePermission("reconciliation:run")
  @PostMapping("/finance-records/preview")
  public ApiResponse<Map<String, Object>> previewFinanceRecords(@RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(financeRecordImportService.previewRecords(file));
  }

  @RequirePermission("import:view")
  @GetMapping("/{jobId}")
  public ApiResponse<Map<String, Object>> getJob(@PathVariable Long jobId) {
    return ApiResponse.ok(importJobService.getJob(jobId));
  }

  @RequirePermission("import:view")
  @GetMapping("/{jobId}/errors")
  public ApiResponse<List<Map<String, Object>>> errors(@PathVariable Long jobId) {
    return ApiResponse.ok(importJobService.listErrors(jobId));
  }

  @RequirePermission("import:view")
  @GetMapping(value = "/{jobId}/errors/download", produces = "text/csv")
  public ResponseEntity<String> downloadErrors(@PathVariable Long jobId) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-errors-" + jobId + ".csv\"")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(importJobService.downloadErrors(jobId));
  }
}
