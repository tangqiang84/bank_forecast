package com.bankforecast.api;

import com.bankforecast.bank.BankTemplateMappingService;
import com.bankforecast.common.ApiResponse;
import com.bankforecast.security.RequirePermission;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bank-templates")
public class BankTemplateController {
  private final BankTemplateMappingService templateMappingService;

  public BankTemplateController(BankTemplateMappingService templateMappingService) {
    this.templateMappingService = templateMappingService;
  }

  @RequirePermission("template:manage")
  @GetMapping
  public ApiResponse<Map<String, Object>> overview() {
    return ApiResponse.ok(templateMappingService.overview(templateMappingService.currentTenantId()));
  }

  @RequirePermission("template:manage")
  @PostMapping("/mappings")
  public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
    return ApiResponse.ok(templateMappingService.create(
        templateMappingService.currentTenantId(), templateMappingService.currentUserId(), request));
  }

  @RequirePermission("template:manage")
  @PutMapping("/mappings/{id:\\d+}")
  public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> request) {
    return ApiResponse.ok(templateMappingService.update(templateMappingService.currentTenantId(), id, request));
  }

  @RequirePermission("template:manage")
  @DeleteMapping("/mappings/{id:\\d+}")
  public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
    return ApiResponse.ok(templateMappingService.delete(templateMappingService.currentTenantId(), id));
  }
}
