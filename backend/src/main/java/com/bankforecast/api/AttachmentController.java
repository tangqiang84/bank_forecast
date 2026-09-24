package com.bankforecast.api;

import com.bankforecast.security.RequirePermission;
import com.bankforecast.attachment.AttachmentService;
import com.bankforecast.common.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/matching/exceptions")
public class AttachmentController {
  private final AttachmentService attachmentService;
  public AttachmentController(AttachmentService attachmentService) { this.attachmentService = attachmentService; }

  @RequirePermission("attachment:manage")
  @PostMapping("/{exceptionId}/attachments")
  public ApiResponse<Map<String, Object>> upload(@PathVariable Long exceptionId, @RequestParam("file") MultipartFile file) { return ApiResponse.ok(attachmentService.upload(exceptionId, file)); }

  @RequirePermission("attachment:view")
  @GetMapping("/{exceptionId}/attachments")
  public ApiResponse<List<Map<String, Object>>> list(@PathVariable Long exceptionId) { return ApiResponse.ok(attachmentService.list(exceptionId)); }

  @RequirePermission("attachment:view")
  @GetMapping("/attachments/{attachmentId}/download")
  public ResponseEntity<byte[]> download(@PathVariable Long attachmentId) {
    Map<String, Object> row = attachmentService.download(attachmentId);
    return response(row, "attachment");
  }

  @RequirePermission("attachment:view")
  @GetMapping("/attachments/{attachmentId}/preview")
  public ResponseEntity<byte[]> preview(@PathVariable Long attachmentId) {
    return response(attachmentService.download(attachmentId), "inline");
  }

  @org.springframework.web.bind.annotation.DeleteMapping("/attachments/{attachmentId}")
  public ApiResponse<Map<String, Object>> delete(@PathVariable Long attachmentId) {
    attachmentService.delete(attachmentId);
    return ApiResponse.ok(java.util.Collections.singletonMap("deleted", true));
  }

  private ResponseEntity<byte[]> response(Map<String, Object> row, String disposition) {
    String fileName = String.valueOf(row.get("file_name")).replace("\"", "");
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename=\"" + fileName + "\"")
        .contentType(MediaType.parseMediaType(String.valueOf(row.get("content_type"))))
        .body(attachmentService.content(row));
  }
}
