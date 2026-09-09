package com.bankforecast.api;

import com.bankforecast.bank.BankTransactionService;
import com.bankforecast.common.ApiResponse;
import java.util.Map;
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

  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
    return ApiResponse.ok(bankTransactionService.list(page, pageSize));
  }
}
