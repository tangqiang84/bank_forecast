package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.rule.RuleCenterService;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import com.bankforecast.security.RequirePermission;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rules")
public class RuleCenterController {
  private final RuleCenterService ruleCenterService;
  private final com.bankforecast.service.ProjectService projectService;

  public RuleCenterController(RuleCenterService ruleCenterService,
      com.bankforecast.service.ProjectService projectService) {
    this.ruleCenterService = ruleCenterService;
    this.projectService = projectService;
  }

  @RequirePermission("project:view")
  @GetMapping("/risk-rules")
  public ApiResponse<List<Map<String, Object>>> riskRules() {
    return ApiResponse.ok(projectService.riskRules(requireAuth().getTenantId()));
  }

  @RequirePermission("project:rule")
  @PutMapping("/risk-rules/{ruleCode}")
  public ApiResponse<Map<String, Object>> updateRiskRule(@PathVariable String ruleCode,
      @RequestBody Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    return ApiResponse.ok(projectService.updateRiskRule(principal.getTenantId(), principal.getUserId(), ruleCode, request));
  }

  @RequirePermission("project:view")
  @GetMapping("/risk-rules/{ruleCode}/versions")
  public ApiResponse<List<Map<String, Object>>> versions(@PathVariable String ruleCode) {
    return ApiResponse.ok(ruleCenterService.versions(requireAuth().getTenantId(), ruleCode));
  }

  @RequirePermission("project:rule")
  @PostMapping("/risk-rules/{ruleCode}/rollback")
  public ApiResponse<Map<String, Object>> rollback(@PathVariable String ruleCode,
      @RequestBody Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    return ApiResponse.ok(ruleCenterService.rollback(
        principal.getTenantId(), principal.getUserId(), ruleCode, versionNo(request)));
  }

  @RequirePermission("project:view")
  @GetMapping("/config")
  public ApiResponse<Map<String, Object>> config() {
    return ApiResponse.ok(ruleCenterService.config(requireAuth().getTenantId()));
  }

  @RequirePermission("project:rule")
  @PutMapping("/config")
  public ApiResponse<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    return ApiResponse.ok(ruleCenterService.updateConfig(principal.getTenantId(), principal.getUserId(), request));
  }

  @RequirePermission("project:rule")
  @PostMapping("/industry-template")
  public ApiResponse<Map<String, Object>> applyIndustryTemplate(@RequestBody Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    Object industry = request == null ? null : request.get("industry");
    return ApiResponse.ok(ruleCenterService.applyIndustryTemplate(
        principal.getTenantId(), principal.getUserId(), industry == null ? null : String.valueOf(industry)));
  }

  private int versionNo(Map<String, Object> request) {
    Object value = request == null ? null : request.get("version_no");
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "version_no 格式不正确");
    }
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
