package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.matching.CounterpartyAliasService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/counterparty-aliases")
public class CounterpartyAliasController {
  private final CounterpartyAliasService counterpartyAliasService;

  public CounterpartyAliasController(CounterpartyAliasService counterpartyAliasService) {
    this.counterpartyAliasService = counterpartyAliasService;
  }

  @RequirePermission("contract:view")
  @GetMapping
  public ApiResponse<List<Map<String, Object>>> list() {
    return ApiResponse.ok(counterpartyAliasService.list(counterpartyAliasService.currentTenantId()));
  }

  @RequirePermission("contract:import")
  @PostMapping
  public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
    return ApiResponse.ok(counterpartyAliasService.create(
        counterpartyAliasService.currentTenantId(), counterpartyAliasService.currentUserId(), request));
  }

  @RequirePermission("contract:import")
  @PutMapping("/{id:\\d+}")
  public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> request) {
    return ApiResponse.ok(counterpartyAliasService.update(
        counterpartyAliasService.currentTenantId(), id, request));
  }

  @RequirePermission("contract:import")
  @DeleteMapping("/{id:\\d+}")
  public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
    return ApiResponse.ok(counterpartyAliasService.delete(counterpartyAliasService.currentTenantId(), id));
  }
}
