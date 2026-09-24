package com.bankforecast.api;

import com.bankforecast.security.RequirePermission;
import com.bankforecast.common.ApiResponse;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import com.bankforecast.security.DataScopeService;
import com.bankforecast.service.ProjectService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
  private final ProjectService projectService;
  private final DataScopeService dataScopeService;

  public ProjectController(ProjectService projectService, DataScopeService dataScopeService) {
    this.projectService = projectService;
    this.dataScopeService = dataScopeService;
  }

  @RequirePermission("project:view")
  @GetMapping
  public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page, @RequestParam(name = "page_size", defaultValue = "20") int pageSize, @RequestParam(name = "project_no", required = false) String projectNo, @RequestParam(name = "customer_name", required = false) String customerName, @RequestParam(name = "project_status", required = false) String projectStatus) {
    AuthPrincipal principal = requireAuth();
    return ApiResponse.ok(projectService.list(principal.getTenantId(), page, pageSize, projectNo, customerName, projectStatus, dataScopeService.projectScopeOrNull(principal)));
  }

  @RequirePermission("project:view")
  @GetMapping("/risk-rules")
  public ApiResponse<List<Map<String, Object>>> riskRules() { return ApiResponse.ok(projectService.riskRules(requireAuth().getTenantId())); }

  @RequirePermission("project:rule")
  @PutMapping("/risk-rules/{ruleCode}")
  public ApiResponse<Map<String, Object>> updateRiskRule(@PathVariable String ruleCode, @RequestBody Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    return ApiResponse.ok(projectService.updateRiskRule(principal.getTenantId(), principal.getUserId(), ruleCode, request));
  }

  @RequirePermission("project:manage")
  @PutMapping("/{id:\\d+}")
  public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    return ApiResponse.ok(projectService.update(principal.getTenantId(), principal.getUserId(), id, request));
  }

  @RequirePermission("project:manage")
  @PostMapping("/batch-status")
  public ApiResponse<Map<String, Object>> batchStatus(@RequestBody Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    return ApiResponse.ok(projectService.batchStatus(principal.getTenantId(), longList(request.get("project_ids")), optionalText(request, "project_status")));
  }

  @RequirePermission("project:view")
  @GetMapping("/{id:\\d+}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    AuthPrincipal principal = requireAuth();
    return ApiResponse.ok(projectService.detail(principal.getTenantId(), id, dataScopeService.projectScopeOrNull(principal)));
  }

  private List<Long> longList(Object value) { if (!(value instanceof List)) return java.util.Collections.emptyList(); List<Long> ids = new java.util.ArrayList<>(); for (Object item : (List<?>) value) try { ids.add(Long.valueOf(String.valueOf(item))); } catch (NumberFormatException ignored) { } return ids; }
  private String optionalText(Map<String, Object> request, String field) { Object value = request == null ? null : request.get(field); return value == null || String.valueOf(value).trim().isEmpty() ? null : String.valueOf(value).trim(); }
  private AuthPrincipal requireAuth() { AuthPrincipal principal = AuthContext.get(); if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录"); return principal; }
}
