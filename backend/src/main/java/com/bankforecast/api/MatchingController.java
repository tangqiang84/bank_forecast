package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.api.dto.RejectMatchResultRequest;
import com.bankforecast.matching.MatchingService;
import javax.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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

  @PostMapping("/results/{id}/confirm")
  public ApiResponse<Map<String, Object>> confirm(@PathVariable Long id) {
    return ApiResponse.ok(matchingService.confirmResult(id));
  }

  @PostMapping("/results/{id}/reject")
  public ApiResponse<Map<String, Object>> reject(@PathVariable Long id,
      @Valid @RequestBody(required = false) RejectMatchResultRequest request) {
    return ApiResponse.ok(matchingService.rejectResult(id, request == null ? null : request.getReason()));
  }
}
