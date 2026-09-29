package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.finance.FinanceReconciliationService;
import com.bankforecast.security.RequirePermission;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/finance-records")
public class FinanceRecordController {
  private final FinanceReconciliationService reconciliationService;

  public FinanceRecordController(FinanceReconciliationService reconciliationService) {
    this.reconciliationService = reconciliationService;
  }

  @RequirePermission("reconciliation:view")
  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
      @RequestParam(name = "record_type", required = false) String recordType,
      @RequestParam(required = false) String subject,
      @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate dateFrom,
      @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate dateTo) {
    return ApiResponse.ok(reconciliationService.financeRecords(page, pageSize, recordType, subject, dateFrom, dateTo));
  }
}
