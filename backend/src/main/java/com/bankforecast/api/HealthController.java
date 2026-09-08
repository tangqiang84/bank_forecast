package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

  @GetMapping("/health")
  public ApiResponse<Map<String, Object>> health() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("service", "bank-fund-connector-backend");
    data.put("status", "UP");
    data.put("timestamp", OffsetDateTime.now().toString());
    return ApiResponse.ok(data);
  }
}
