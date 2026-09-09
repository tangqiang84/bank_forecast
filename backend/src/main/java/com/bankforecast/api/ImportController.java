package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.contract.ContractImportService;
import com.bankforecast.importjob.ImportJobService;
import java.util.Map;
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

  public ImportController(ImportJobService importJobService, ContractImportService contractImportService) {
    this.importJobService = importJobService;
    this.contractImportService = contractImportService;
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

  @GetMapping("/{jobId}")
  public ApiResponse<Map<String, Object>> getJob(@PathVariable Long jobId) {
    return ApiResponse.ok(importJobService.getJob(jobId));
  }
}
