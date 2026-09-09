package com.bankforecast.api;

import com.bankforecast.api.dto.ForecastRequest;
import com.bankforecast.api.dto.ForecastResponse;
import com.bankforecast.common.ApiResponse;
import com.bankforecast.service.CashflowForecastService;
import com.bankforecast.service.ForecastJobService;
import javax.validation.Valid;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
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

  @GetMapping("/sample")
  public ApiResponse<Map<String, Object>> sample() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("history", Arrays.asList("120000.00", "128000.00", "133000.00", "140000.00"));
    data.put("hint", "POST /api/v1/forecast/cashflow");
    return ApiResponse.ok(data);
  }

  @PostMapping("/cashflow")
  public ApiResponse<ForecastResponse> forecast(@Valid @RequestBody ForecastRequest request) {
    return ApiResponse.ok(forecastService.forecast(request));
  }

  @PostMapping("/cashflow/jobs")
  public ApiResponse<Map<String, Object>> createJob(
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int horizon,
      @org.springframework.web.bind.annotation.RequestParam(name = "window_size", defaultValue = "3") int windowSize) {
    return ApiResponse.ok(forecastJobService.run(horizon, windowSize));
  }

  @GetMapping("/cashflow/latest")
  public ApiResponse<Map<String, Object>> latest() {
    return ApiResponse.ok(forecastJobService.latest());
  }

  @GetMapping("/cashflow/jobs/{id}")
  public ApiResponse<Map<String, Object>> detail(@org.springframework.web.bind.annotation.PathVariable Long id) {
    return ApiResponse.ok(forecastJobService.detail(id));
  }

  @PostMapping("/cashflow/jobs/{id}/retry")
  public ApiResponse<Map<String, Object>> retry(@org.springframework.web.bind.annotation.PathVariable Long id) {
    return ApiResponse.ok(forecastJobService.retry(id));
  }
}
