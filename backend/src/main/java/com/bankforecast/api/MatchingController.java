package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.matching.MatchingService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/matching")
public class MatchingController {
  private final MatchingService matchingService;

  public MatchingController(MatchingService matchingService) { this.matchingService = matchingService; }

  @PostMapping("/receivables/run")
  public ApiResponse<Map<String, Object>> run() { return ApiResponse.ok(matchingService.runReceivableMatching()); }

  @GetMapping("/results")
  public ApiResponse<Map<String, Object>> results() { return ApiResponse.ok(matchingService.listResults()); }

  @GetMapping("/exceptions")
  public ApiResponse<List<Map<String, Object>>> exceptions() { return ApiResponse.ok(matchingService.listExceptions()); }
}
