package com.bankforecast.api;

import com.bankforecast.attachment.AttachmentService;
import com.bankforecast.common.ApiResponse;
import java.util.List;
import java.util.Map;
import java.sql.Blob;
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

  @PostMapping("/{exceptionId}/attachments")
  public ApiResponse<Map<String, Object>> upload(@PathVariable Long exceptionId, @RequestParam("file") MultipartFile file) { return ApiResponse.ok(attachmentService.upload(exceptionId, file)); }

  @GetMapping("/{exceptionId}/attachments")
  public ApiResponse<List<Map<String, Object>>> list(@PathVariable Long exceptionId) { return ApiResponse.ok(attachmentService.list(exceptionId)); }

  @GetMapping("/attachments/{attachmentId}/download")
  public ResponseEntity<byte[]> download(@PathVariable Long attachmentId) {
    Map<String, Object> row = attachmentService.download(attachmentId);
    String fileName = String.valueOf(row.get("file_name")).replace("\"", "");
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
        .contentType(MediaType.parseMediaType(String.valueOf(row.get("content_type"))))
        .body(toBytes(row.get("file_content")));
  }

  private byte[] toBytes(Object content) {
    if (content instanceof byte[]) return (byte[]) content;
    if (content instanceof Blob) {
      try {
        Blob blob = (Blob) content;
        return blob.getBytes(1, (int) blob.length());
      } catch (Exception ex) {
        throw new IllegalStateException("读取附件内容失败", ex);
      }
    }
    throw new IllegalStateException("附件内容格式不支持");
  }
}
