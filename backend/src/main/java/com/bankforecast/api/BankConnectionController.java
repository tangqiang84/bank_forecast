package com.bankforecast.api;

import com.bankforecast.bank.BankConnectionService;
import com.bankforecast.common.ApiResponse;
import com.bankforecast.security.RequirePermission;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/bank-connections")
public class BankConnectionController {
  private final BankConnectionService bankConnectionService;

  public BankConnectionController(BankConnectionService bankConnectionService) {
    this.bankConnectionService = bankConnectionService;
  }

  @RequirePermission("account:view")
  @GetMapping
  public ApiResponse<List<Map<String, Object>>> list() {
    return ApiResponse.ok(bankConnectionService.list(bankConnectionService.currentTenantId()));
  }

  @RequirePermission("account:view")
  @GetMapping("/templates")
  public ApiResponse<List<Map<String, Object>>> templates() {
    return ApiResponse.ok(bankConnectionService.templates());
  }

  @RequirePermission("account:manage")
  @PostMapping
  public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
    return ApiResponse.ok(bankConnectionService.create(
        bankConnectionService.currentTenantId(), bankConnectionService.currentUserId(), request));
  }

  @RequirePermission("account:manage")
  @PutMapping("/{id:\\d+}")
  public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> request) {
    return ApiResponse.ok(bankConnectionService.update(bankConnectionService.currentTenantId(), id, request));
  }

  @RequirePermission("account:manage")
  @DeleteMapping("/{id:\\d+}")
  public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
    return ApiResponse.ok(bankConnectionService.delete(bankConnectionService.currentTenantId(), id));
  }

  @RequirePermission("account:manage")
  @PostMapping("/{id:\\d+}/test")
  public ApiResponse<Map<String, Object>> test(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(bankConnectionService.test(bankConnectionService.currentTenantId(), id, file));
  }
}
