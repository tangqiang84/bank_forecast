package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import com.bankforecast.service.ContractService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {
  private final ContractService contractService;
  public ContractController(ContractService contractService) { this.contractService = contractService; }

  @GetMapping
  public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page, @RequestParam(name = "page_size", defaultValue = "20") int pageSize, @RequestParam(name = "contract_no", required = false) String contractNo, @RequestParam(name = "project_no", required = false) String projectNo, @RequestParam(required = false) String status) {
    return ApiResponse.ok(contractService.list(requireAuth().getTenantId(), page, pageSize, contractNo, projectNo, status));
  }

  @GetMapping("/receivables")
  public ApiResponse<Map<String, Object>> receivables(@RequestParam(defaultValue = "1") int page, @RequestParam(name = "page_size", defaultValue = "20") int pageSize, @RequestParam(name = "contract_no", required = false) String contractNo, @RequestParam(name = "project_no", required = false) String projectNo, @RequestParam(name = "date_from", required = false) LocalDate dateFrom, @RequestParam(name = "date_to", required = false) LocalDate dateTo, @RequestParam(required = false) String status) {
    return ApiResponse.ok(contractService.receivables(requireAuth().getTenantId(), page, pageSize, contractNo, projectNo, dateFrom, dateTo, status));
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) { return ApiResponse.ok(contractService.detail(requireAuth().getTenantId(), id)); }

  private AuthPrincipal requireAuth() { AuthPrincipal principal = AuthContext.get(); if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录"); return principal; }
}
