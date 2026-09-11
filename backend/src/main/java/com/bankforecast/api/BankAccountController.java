package com.bankforecast.api;

import com.bankforecast.bank.BankAccountRepository;
import com.bankforecast.audit.AuditService;
import com.bankforecast.common.ApiResponse;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.Map;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bank-accounts")
public class BankAccountController {

  private final BankAccountRepository bankAccountRepository;
  private final AuditService auditService;

  public BankAccountController(BankAccountRepository bankAccountRepository, AuditService auditService) {
    this.bankAccountRepository = bankAccountRepository;
    this.auditService = auditService;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
    AuthPrincipal principal = requireAuth();
    return ApiResponse.ok(bankAccountRepository.listByTenant(principal.getTenantId(), page, pageSize));
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    AuthPrincipal principal = requireAuth();
    return ApiResponse.ok(bankAccountRepository.findById(principal.getTenantId(), id));
  }

  @PostMapping
  public ApiResponse<Map<String, Object>> create(@Valid @RequestBody BankAccountRequest request) {
    AuthPrincipal principal = requireAuth();
    Map<String, Object> result = bankAccountRepository.create(principal.getTenantId(), request.getBankCode(), request.getBankName(), request.getAccountName(), request.getAccountNo(), request.getCurrency(), request.getCurrentBalance());
    auditService.record("CREATE_BANK_ACCOUNT", "bank_account", String.valueOf(result.get("id")), "bank_code=" + request.getBankCode());
    return ApiResponse.ok(result);
  }

  @PutMapping("/{id}")
  public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @Valid @RequestBody BankAccountRequest request) {
    AuthPrincipal principal = requireAuth();
    Map<String, Object> result = bankAccountRepository.update(principal.getTenantId(), id, request.getBankCode(), request.getBankName(), request.getAccountName(), request.getAccountNo(), request.getCurrency(), request.getCurrentBalance());
    auditService.record("UPDATE_BANK_ACCOUNT", "bank_account", String.valueOf(id), "bank_code=" + request.getBankCode());
    return ApiResponse.ok(result);
  }

  @PostMapping("/{id}/close")
  public ApiResponse<Map<String, Object>> close(@PathVariable Long id) {
    AuthPrincipal principal = requireAuth();
    Map<String, Object> result = bankAccountRepository.close(principal.getTenantId(), id);
    auditService.record("CLOSE_BANK_ACCOUNT", "bank_account", String.valueOf(id), "status=closed");
    return ApiResponse.ok(result);
  }

  @PostMapping("/idle-scan")
  public ApiResponse<Map<String, Object>> idleScan() {
    AuthPrincipal principal = requireAuth();
    int updated = bankAccountRepository.refreshIdleStatuses(principal.getTenantId());
    auditService.record("SCAN_IDLE_BANK_ACCOUNTS", "bank_account", null, "updated=" + updated);
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("updated_accounts", updated);
    result.put("accounts", bankAccountRepository.listByTenant(principal.getTenantId()));
    return ApiResponse.ok(result);
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
