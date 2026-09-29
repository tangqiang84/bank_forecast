package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.security.RequirePermission;
import com.bankforecast.system.SystemAdminService;
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
@RequestMapping("/api/v1/system")
public class SystemAdminController {
  private final SystemAdminService systemAdminService;

  public SystemAdminController(SystemAdminService systemAdminService) {
    this.systemAdminService = systemAdminService;
  }

  @RequirePermission("system:manage")
  @GetMapping("/users")
  public ApiResponse<Map<String, Object>> users(@RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
      @RequestParam(required = false) String keyword) {
    return ApiResponse.ok(systemAdminService.listUsers(systemAdminService.currentTenantId(), page, pageSize, keyword));
  }

  @RequirePermission("system:manage")
  @PostMapping("/users")
  public ApiResponse<Map<String, Object>> createUser(@RequestBody Map<String, Object> request) {
    return ApiResponse.ok(systemAdminService.createUser(
        systemAdminService.currentTenantId(), systemAdminService.currentUserId(), request));
  }

  @RequirePermission("system:manage")
  @PutMapping("/users/{id:\\d+}")
  public ApiResponse<Map<String, Object>> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> request) {
    return ApiResponse.ok(systemAdminService.updateUser(
        systemAdminService.currentTenantId(), systemAdminService.currentUserId(), id, request));
  }

  @RequirePermission("system:manage")
  @PostMapping("/users/{id:\\d+}/reset-password")
  public ApiResponse<Map<String, Object>> resetPassword(@PathVariable Long id, @RequestBody Map<String, Object> request) {
    return ApiResponse.ok(systemAdminService.resetPassword(systemAdminService.currentTenantId(), id, request));
  }

  @RequirePermission("system:manage")
  @GetMapping("/users/{id:\\d+}/project-scope")
  public ApiResponse<List<Long>> projectScope(@PathVariable Long id) {
    return ApiResponse.ok(systemAdminService.projectScope(systemAdminService.currentTenantId(), id));
  }

  @RequirePermission("system:manage")
  @PutMapping("/users/{id:\\d+}/project-scope")
  public ApiResponse<Map<String, Object>> updateProjectScope(@PathVariable Long id, @RequestBody Map<String, Object> request) {
    return ApiResponse.ok(systemAdminService.updateProjectScope(systemAdminService.currentTenantId(), id, request));
  }

  @RequirePermission("system:manage")
  @GetMapping("/roles")
  public ApiResponse<List<Map<String, Object>>> roles() {
    return ApiResponse.ok(systemAdminService.listRoles(systemAdminService.currentTenantId()));
  }

  @RequirePermission("system:manage")
  @PutMapping("/roles/{id:\\d+}/permissions")
  public ApiResponse<Map<String, Object>> updateRolePermissions(@PathVariable Long id, @RequestBody Map<String, Object> request) {
    return ApiResponse.ok(systemAdminService.updateRolePermissions(systemAdminService.currentTenantId(), id, request));
  }

  @RequirePermission("system:manage")
  @GetMapping("/permissions")
  public ApiResponse<List<Map<String, Object>>> permissions() {
    return ApiResponse.ok(systemAdminService.listPermissions());
  }
}
