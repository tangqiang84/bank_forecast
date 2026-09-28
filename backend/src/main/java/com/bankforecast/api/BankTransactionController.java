package com.bankforecast.api;

import com.bankforecast.security.RequirePermission;
import com.bankforecast.bank.BankTransactionService;
import com.bankforecast.common.ApiResponse;
import com.bankforecast.api.dto.TransactionBatchClassifyRequest;
import com.bankforecast.api.dto.TransactionBatchUnlinkRequest;
import com.bankforecast.api.dto.TransactionClassifyRequest;
import com.bankforecast.api.dto.TransactionUnlinkRequest;
import java.util.Map;
import java.time.LocalDate;
import javax.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bank-transactions")
public class BankTransactionController {

  private final BankTransactionService bankTransactionService;

  public BankTransactionController(BankTransactionService bankTransactionService) {
    this.bankTransactionService = bankTransactionService;
  }

  @RequirePermission("transaction:view")
  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
      @RequestParam(name = "bank_account_id", required = false) Long bankAccountId,
      @RequestParam(name = "contract_no", required = false) String contractNo,
      @RequestParam(name = "project_no", required = false) String projectNo,
      @RequestParam(name = "date_from", required = false) LocalDate dateFrom,
      @RequestParam(name = "date_to", required = false) LocalDate dateTo,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(name = "amount_min", required = false) java.math.BigDecimal amountMin,
      @RequestParam(name = "amount_max", required = false) java.math.BigDecimal amountMax) {
    return ApiResponse.ok(bankTransactionService.list(page, pageSize, bankAccountId, contractNo, projectNo, dateFrom, dateTo, status, keyword, amountMin, amountMax));
  }

  @RequirePermission("transaction:view")
  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@org.springframework.web.bind.annotation.PathVariable Long id) {
    return ApiResponse.ok(bankTransactionService.detail(id));
  }

  @RequirePermission("transaction:import")
  @org.springframework.web.bind.annotation.PostMapping("/{id}/manual-classify")
  public ApiResponse<Map<String, Object>> classify(@org.springframework.web.bind.annotation.PathVariable Long id,
      @Valid @org.springframework.web.bind.annotation.RequestBody TransactionClassifyRequest request) {
    return ApiResponse.ok(bankTransactionService.classify(id, request));
  }

  @RequirePermission("transaction:import")
  @org.springframework.web.bind.annotation.PostMapping("/{id}/unlink")
  public ApiResponse<Map<String, Object>> unlink(@org.springframework.web.bind.annotation.PathVariable Long id,
      @Valid @org.springframework.web.bind.annotation.RequestBody TransactionUnlinkRequest request) {
    return ApiResponse.ok(bankTransactionService.unlink(id, request.getReason()));
  }

  @RequirePermission("transaction:import")
  @org.springframework.web.bind.annotation.PostMapping("/batch-classify")
  public ApiResponse<Map<String, Object>> batchClassify(
      @Valid @org.springframework.web.bind.annotation.RequestBody TransactionBatchClassifyRequest request) {
    return ApiResponse.ok(bankTransactionService.batchClassify(
        request.getTransaction_ids(), request.getCategory(), request.getPurpose(), request.getRemark()));
  }

  @RequirePermission("transaction:import")
  @org.springframework.web.bind.annotation.PostMapping("/batch-unlink")
  public ApiResponse<Map<String, Object>> batchUnlink(
      @Valid @org.springframework.web.bind.annotation.RequestBody TransactionBatchUnlinkRequest request) {
    return ApiResponse.ok(bankTransactionService.batchUnlink(request.getTransaction_ids(), request.getReason()));
  }

  @RequirePermission("transaction:export")
  @GetMapping(value = "/export")
  public ResponseEntity<?> export(@RequestParam(name = "bank_account_id", required = false) Long bankAccountId,
      @RequestParam(name = "date_from", required = false) LocalDate dateFrom,
      @RequestParam(name = "date_to", required = false) LocalDate dateTo,
      @RequestParam(required = false) String status, @RequestParam(required = false) String category,
      @RequestParam(required = false) String keyword,
      @RequestParam(name = "amount_min", required = false) java.math.BigDecimal amountMin,
      @RequestParam(name = "amount_max", required = false) java.math.BigDecimal amountMax,
      @RequestParam(required = false) String format) {
    if ("xlsx".equalsIgnoreCase(format == null ? "" : format.trim())) {
      return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=bank-transactions.xlsx")
          .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
          .body(bankTransactionService.exportXlsx(bankAccountId, dateFrom, dateTo, status, category, keyword, amountMin, amountMax));
    }
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=bank-transactions.csv")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(bankTransactionService.exportCsv(bankAccountId, dateFrom, dateTo, status, category, keyword, amountMin, amountMax));
  }
}
