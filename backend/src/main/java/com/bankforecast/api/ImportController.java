package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
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

  @PostMapping("/contracts")
  public ApiResponse<Map<String, Object>> importContracts(@RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(contractImportService.importContracts(file));
  }

  @PostMapping("/bank-statements")
  public ApiResponse<Map<String, Object>> importBankStatements(
      @RequestParam("bank_account_id") Long bankAccountId,
      @RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(importJobService.importBankStatements(bankAccountId, file));
  }

  @PostMapping("/bank-statements/preview")
  public ApiResponse<Map<String, Object>> previewBankStatements(
      @RequestParam("bank_account_id") Long bankAccountId,
      @RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(importJobService.previewBankStatements(bankAccountId, file));
  }

  @GetMapping("/{jobId}/preview")
  public ApiResponse<Map<String, Object>> preview(@PathVariable Long jobId) {
    return ApiResponse.ok(importJobService.getPreview(jobId));
  }

  @PostMapping("/{jobId}/confirm")
  public ApiResponse<Map<String, Object>> confirm(@PathVariable Long jobId) {
    return ApiResponse.ok(importJobService.confirmPreview(jobId));
  }

  @PostMapping("/{jobId}/retry-errors")
  public ApiResponse<Map<String, Object>> retryErrors(@PathVariable Long jobId, @org.springframework.web.bind.annotation.RequestBody Map<String, Object> request) {
    return ApiResponse.ok(importJobService.retryPreviewErrors(jobId, request));
  }

  @PostMapping("/finance-records")
  public ApiResponse<Map<String, Object>> importFinanceRecords(@RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(financeRecordImportService.importRecords(file));
  }

  @GetMapping("/{jobId}")
  public ApiResponse<Map<String, Object>> getJob(@PathVariable Long jobId) {
    return ApiResponse.ok(importJobService.getJob(jobId));
  }

  @GetMapping("/{jobId}/errors")
  public ApiResponse<List<Map<String, Object>>> errors(@PathVariable Long jobId) {
    return ApiResponse.ok(importJobService.listErrors(jobId));
  }

  @GetMapping(value = "/{jobId}/errors/download", produces = "text/csv")
  public ResponseEntity<String> downloadErrors(@PathVariable Long jobId) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-errors-" + jobId + ".csv\"")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(importJobService.downloadErrors(jobId));
  }
}
