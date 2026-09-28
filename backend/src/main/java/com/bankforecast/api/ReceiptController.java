package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.receipt.ReceiptService;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.RequirePermission;
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
@RequestMapping("/api/v1/receipts")
public class ReceiptController {
  private final ReceiptService receiptService;

  public ReceiptController(ReceiptService receiptService) {
    this.receiptService = receiptService;
  }

  @RequirePermission("receipt:view")
  @GetMapping
  public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
      @RequestParam(name = "receipt_no", required = false) String receiptNo,
      @RequestParam(name = "transaction_no", required = false) String transactionNo) {
    return ApiResponse.ok(receiptService.list(requireAuth().getTenantId(), page, pageSize, receiptNo, transactionNo));
  }

  @RequirePermission("receipt:view")
  @GetMapping("/{id:\\d+}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    return ApiResponse.ok(receiptService.detail(requireAuth().getTenantId(), id));
  }

  @RequirePermission("receipt:import")
  @PostMapping("/{id:\\d+}/image")
  public ApiResponse<Map<String, Object>> uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(receiptService.uploadImage(id, file));
  }

  @RequirePermission("receipt:view")
  @GetMapping("/{id:\\d+}/image/download")
  public ResponseEntity<byte[]> downloadImage(@PathVariable Long id) {
    return imageResponse(id, "attachment");
  }

  @RequirePermission("receipt:view")
  @GetMapping("/{id:\\d+}/image/preview")
  public ResponseEntity<byte[]> previewImage(@PathVariable Long id) {
    return imageResponse(id, "inline");
  }

  private ResponseEntity<byte[]> imageResponse(Long id, String disposition) {
    Map<String, Object> receipt = receiptService.image(id);
    String fileName = String.valueOf(receipt.get("image_file_name")).replace("\"", "");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename=\"" + fileName + "\"")
        .contentType(MediaType.parseMediaType(String.valueOf(receipt.get("image_content_type"))))
        .body(receiptService.imageContent(receipt));
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
