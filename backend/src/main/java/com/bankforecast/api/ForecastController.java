package com.bankforecast.api;

import com.bankforecast.security.RequirePermission;
import com.bankforecast.api.dto.ForecastRequest;
import com.bankforecast.api.dto.ForecastResponse;
import com.bankforecast.common.ApiResponse;
import com.bankforecast.service.CashflowForecastService;
import com.bankforecast.service.ForecastJobService;
import javax.validation.Valid;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/forecast")
public class ForecastController {

  private final CashflowForecastService forecastService;
  private final ForecastJobService forecastJobService;

  public ForecastController(CashflowForecastService forecastService, ForecastJobService forecastJobService) {
    this.forecastService = forecastService;
    this.forecastJobService = forecastJobService;
  }

  @RequirePermission("forecast:view")
  @GetMapping("/sample")
  public ApiResponse<Map<String, Object>> sample() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("history", Arrays.asList("120000.00", "128000.00", "133000.00", "140000.00"));
    data.put("hint", "POST /api/v1/forecast/cashflow");
    return ApiResponse.ok(data);
  }

  @RequirePermission("forecast:run")
  @PostMapping("/cashflow")
  public ApiResponse<ForecastResponse> forecast(@Valid @RequestBody ForecastRequest request) {
    return ApiResponse.ok(forecastService.forecast(request));
  }

  @RequirePermission("forecast:run")
  @PostMapping("/cashflow/jobs")
  public ApiResponse<Map<String, Object>> createJob(
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int horizon,
      @org.springframework.web.bind.annotation.RequestParam(name = "window_size", defaultValue = "3") int windowSize) {
    return ApiResponse.ok(forecastJobService.run(horizon, windowSize));
  }

  @RequirePermission("forecast:view")
  @GetMapping("/cashflow/latest")
  public ApiResponse<Map<String, Object>> latest() {
    return ApiResponse.ok(forecastJobService.latest());
  }

  @RequirePermission("forecast:view")
  @GetMapping("/cashflow/jobs/{id}")
  public ApiResponse<Map<String, Object>> detail(@org.springframework.web.bind.annotation.PathVariable Long id) {
    return ApiResponse.ok(forecastJobService.detail(id));
  }

  @RequirePermission("forecast:run")
  @PostMapping("/cashflow/jobs/{id}/retry")
  public ApiResponse<Map<String, Object>> retry(@org.springframework.web.bind.annotation.PathVariable Long id) {
    return ApiResponse.ok(forecastJobService.retry(id));
  }

  @RequirePermission("forecast:run")
  @PostMapping("/cashflow/jobs/{id}/actuals")
  public ApiResponse<Map<String, Object>> backfillActuals(@org.springframework.web.bind.annotation.PathVariable Long id) {
    return ApiResponse.ok(forecastJobService.backfillActuals(id));
  }

  @RequirePermission("forecast:view")
  @GetMapping("/models")
  public ApiResponse<List<Map<String, Object>>> models() {
    return ApiResponse.ok(forecastJobService.models());
  }

  @RequirePermission("forecast:model")
  @PostMapping("/models/{version}/activate")
  public ApiResponse<Map<String, Object>> activateModel(@org.springframework.web.bind.annotation.PathVariable String version) {
    return ApiResponse.ok(forecastJobService.activateModel(version));
  }
}
