package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.payable.PayableService;
import com.bankforecast.security.RequirePermission;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payables")
public class PayableController {
  private final PayableService payableService;

  public PayableController(PayableService payableService) {
    this.payableService = payableService;
  }

  @RequirePermission("contract:view")
  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
      @RequestParam(required = false) String status) {
    return ApiResponse.ok(payableService.list(page, pageSize, status));
  }
}
