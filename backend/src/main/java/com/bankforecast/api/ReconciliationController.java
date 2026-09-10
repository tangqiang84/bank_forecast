package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.finance.FinanceReconciliationService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;

@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {
  private final FinanceReconciliationService service;

  public ReconciliationController(FinanceReconciliationService service) { this.service = service; }

  @PostMapping("/run")
  public ApiResponse<Map<String, Object>> run(
      @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate dateFrom,
      @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate dateTo) {
    return ApiResponse.ok(service.run(dateFrom, dateTo));
  }

  @GetMapping("/results")
  public ApiResponse<Map<String, Object>> results(
      @RequestParam(name = "job_id", required = false) Long jobId,
      @RequestParam(name = "difference_type", required = false) String differenceType,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
    return ApiResponse.ok(service.results(jobId, differenceType, page, pageSize));
  }
}
