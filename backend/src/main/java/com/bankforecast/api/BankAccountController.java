package com.bankforecast.api;

import com.bankforecast.bank.BankAccountRepository;
import com.bankforecast.common.ApiResponse;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bank-accounts")
public class BankAccountController {

  private final BankAccountRepository bankAccountRepository;

  public BankAccountController(BankAccountRepository bankAccountRepository) {
    this.bankAccountRepository = bankAccountRepository;
  }

  @GetMapping
  public ApiResponse<List<Map<String, Object>>> list() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    }
    return ApiResponse.ok(bankAccountRepository.listByTenant(principal.getTenantId()));
  }
}
