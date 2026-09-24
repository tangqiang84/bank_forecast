package com.bankforecast.api;

import com.bankforecast.security.RequirePermission;
import com.bankforecast.common.ApiResponse;
import com.bankforecast.report.ReportService;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
  private final ReportService reportService;

  public ReportController(ReportService reportService) { this.reportService = reportService; }

  @RequirePermission("report:generate")
  @PostMapping
  public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
    return ApiResponse.ok(reportService.create(request == null ? null : String.valueOf(request.get("report_type")),
        request == null ? null : castParams(request.get("params_json"))));
  }

  @RequirePermission("report:view")
  @GetMapping
  public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
      @RequestParam(required = false, name = "report_type") String reportType,
      @RequestParam(required = false) String status) {
    return ApiResponse.ok(reportService.list(reportType, status, page, pageSize));
  }

  @RequirePermission("report:view")
  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) { return ApiResponse.ok(reportService.detail(id)); }

  @RequirePermission("report:download")
  @GetMapping(value = "/{id}/download", produces = "text/csv")
  public ResponseEntity<String> download(@PathVariable Long id) {
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report-" + id + ".csv")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(reportService.download(id));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> castParams(Object value) { return value instanceof Map ? (Map<String, Object>) value : java.util.Collections.emptyMap(); }
}
