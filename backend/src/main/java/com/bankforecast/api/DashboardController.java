package com.bankforecast.api;

import com.bankforecast.security.RequirePermission;
import com.bankforecast.common.ApiResponse;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import com.bankforecast.service.DashboardService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
  private final DashboardService dashboardService;
  public DashboardController(DashboardService dashboardService) { this.dashboardService = dashboardService; }

  @RequirePermission("dashboard:view")
  @GetMapping("/overview")
  public ApiResponse<Map<String, Object>> overview() { return ApiResponse.ok(dashboardService.overview(requireAuth().getTenantId())); }

  private AuthPrincipal requireAuth() { AuthPrincipal principal = AuthContext.get(); if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录"); return principal; }
}
