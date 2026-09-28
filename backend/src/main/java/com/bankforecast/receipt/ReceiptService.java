package com.bankforecast.receipt;

import com.bankforecast.attachment.AttachmentStorage;
import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ReceiptService {
  private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024;
  private static final List<String> ALLOWED_IMAGE_TYPES = Arrays.asList(
      "application/pdf", "image/png", "image/jpeg", "image/jpg");

  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;
  private final AttachmentStorage storage;

  public ReceiptService(JdbcTemplate jdbcTemplate, AuditService auditService, AttachmentStorage storage) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
    this.storage = storage;
  }

  public Map<String, Object> list(Long tenantId, int page, int pageSize, String receiptNo, String transactionNo) {
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safeSize;
    String receiptFilter = receiptNo == null || receiptNo.trim().isEmpty() ? null : receiptNo.trim();
    String transactionFilter = transactionNo == null || transactionNo.trim().isEmpty() ? null : transactionNo.trim();
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select " + LIST_COLUMNS + " from receipt where tenant_id = ? and deleted_at is null "
            + "and (? is null or receipt_no = ?) and (? is null or transaction_no = ?) "
            + "order by id desc limit ? offset ?",
        tenantId, receiptFilter, receiptFilter, transactionFilter, transactionFilter, safeSize, offset);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", jdbcTemplate.queryForObject(
        "select count(*) from receipt where tenant_id = ? and deleted_at is null "
            + "and (? is null or receipt_no = ?) and (? is null or transaction_no = ?)",
        Integer.class, tenantId, receiptFilter, receiptFilter, transactionFilter, transactionFilter));
    return data;
  }

  public Map<String, Object> detail(Long tenantId, Long id) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select " + LIST_COLUMNS + " from receipt where id = ? and tenant_id = ? and deleted_at is null",
        id, tenantId);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "回单不存在");
    return rows.get(0);
  }

  @Transactional
  public Map<String, Object> uploadImage(Long receiptId, MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    detail(principal.getTenantId(), receiptId);
    List<Map<String, Object>> imageRows = jdbcTemplate.queryForList(
        "select image_object_key from receipt where id = ? and tenant_id = ?", receiptId, principal.getTenantId());
    String oldKey = imageRows.isEmpty() || imageRows.get(0).get("image_object_key") == null
        ? null : String.valueOf(imageRows.get(0).get("image_object_key"));
    if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "影像文件不能为空");
    if (file.getSize() > MAX_IMAGE_SIZE) throw new BusinessException(ErrorCode.ATTACHMENT_TOO_LARGE, "影像文件不能超过 10MB");
    String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
    if (!ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
      throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "仅支持 PDF、PNG 或 JPG 影像文件");
    }
    String objectKey = principal.getTenantId() + "/receipt/" + receiptId + "/" + UUID.randomUUID().toString();
    try {
      storage.put(objectKey, file.getBytes());
      jdbcTemplate.update("update receipt set image_file_name = ?, image_content_type = ?, image_size = ?, image_object_key = ?, image_uploaded_by = ?, image_uploaded_at = current_timestamp, updated_at = current_timestamp where id = ? and tenant_id = ?",
          file.getOriginalFilename() == null ? "receipt-image" : file.getOriginalFilename(),
          contentType, file.getSize(), objectKey, principal.getUserId(), receiptId, principal.getTenantId());
      if (oldKey != null) storage.delete(oldKey);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.SYSTEM_ERROR, "影像上传失败，请稍后重试");
    }
    auditService.record("UPLOAD_RECEIPT_IMAGE", "receipt", String.valueOf(receiptId), "file_size=" + file.getSize());
    return detail(principal.getTenantId(), receiptId);
  }

  public Map<String, Object> image(Long receiptId) {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select id, image_file_name, image_content_type, image_object_key from receipt where id = ? and tenant_id = ? and deleted_at is null",
        receiptId, principal.getTenantId());
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "回单不存在");
    Map<String, Object> receipt = rows.get(0);
    if (receipt.get("image_object_key") == null) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "该回单尚未上传影像");
    }
    auditService.record("VIEW_RECEIPT_IMAGE", "receipt", String.valueOf(receiptId), "");
    return receipt;
  }

  public byte[] imageContent(Map<String, Object> receipt) {
    try {
      return storage.get(String.valueOf(receipt.get("image_object_key")));
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取影像内容失败，请稍后重试");
    }
  }

  private static final String LIST_COLUMNS =
      "id, import_job_id, bank_transaction_id, bank_account_id, bank_name, receipt_no, print_date, "
          + "transaction_date, transaction_time, currency, payer_name, payer_account_last4, payee_name, "
          + "payee_account_last4, payer_bank, payee_bank, amount, summary, transaction_no, channel, "
          + "verification_code, image_file_name, image_content_type, image_size, image_uploaded_at, "
          + "(image_object_key is not null) as has_image, created_at, updated_at";

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
