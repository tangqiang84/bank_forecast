package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.api.dto.ExceptionActionRequest;
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

  @PostMapping("/exceptions/{id}/assign")
  public ApiResponse<Map<String, Object>> assignException(@PathVariable Long id,
      @Valid @RequestBody(required = false) ExceptionActionRequest request) {
    return ApiResponse.ok(matchingService.assignException(id, request == null ? null : request.getOwnerUserId()));
  }

  @PostMapping("/exceptions/{id}/comment")
  public ApiResponse<Map<String, Object>> commentException(@PathVariable Long id,
      @Valid @RequestBody ExceptionActionRequest request) {
    return ApiResponse.ok(matchingService.commentException(id, request == null ? null : request.getText()));
  }

  @PostMapping("/exceptions/{id}/resolve")
  public ApiResponse<Map<String, Object>> resolveException(@PathVariable Long id,
      @Valid @RequestBody(required = false) ExceptionActionRequest request) {
    return ApiResponse.ok(matchingService.resolveException(id, request == null ? null : request.getText()));
  }

  @PostMapping("/exceptions/{id}/close")
  public ApiResponse<Map<String, Object>> closeException(@PathVariable Long id,
      @Valid @RequestBody(required = false) ExceptionActionRequest request) {
    return ApiResponse.ok(matchingService.closeException(id, request == null ? null : request.getText()));
  }

  @GetMapping("/exceptions/{id}/logs")
  public ApiResponse<List<Map<String, Object>>> exceptionLogs(@PathVariable Long id) {
    return ApiResponse.ok(matchingService.listExceptionLogs(id));
  }
}
