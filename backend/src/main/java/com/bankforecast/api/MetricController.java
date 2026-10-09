package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.metric.MetricService;
import com.bankforecast.security.RequirePermission;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricController {
  private final MetricService metricService;

  public MetricController(MetricService metricService) {
    this.metricService = metricService;
  }

  @RequirePermission("report:generate")
  @PostMapping("/snapshots/collect")
  public ApiResponse<Map<String, Object>> collect(@RequestBody(required = false) Map<String, Object> request) {
    String date = request == null || request.get("date") == null ? null : String.valueOf(request.get("date"));
    return ApiResponse.ok(metricService.collect(
        metricService.currentTenantId(), metricService.currentUserId(), date));
  }

  @RequirePermission("dashboard:view")
  @GetMapping("/snapshots")
  public ApiResponse<List<Map<String, Object>>> series(
      @RequestParam("metric_code") String metricCode,
      @RequestParam(value = "from", required = false) String from,
      @RequestParam(value = "to", required = false) String to) {
    return ApiResponse.ok(metricService.series(metricService.currentTenantId(), metricCode, from, to));
  }

  @RequirePermission("dashboard:view")
  @GetMapping("/pilot-acceptance")
  public ApiResponse<Map<String, Object>> pilotAcceptance() {
    return ApiResponse.ok(metricService.pilotAcceptance(metricService.currentTenantId()));
  }
}
