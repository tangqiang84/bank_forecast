package com.bankforecast.attachment;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {
  private static final long MAX_SIZE = 10 * 1024 * 1024;
  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;
  private final AttachmentStorage storage;

  public AttachmentService(JdbcTemplate jdbcTemplate, AuditService auditService, AttachmentStorage storage) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
    this.storage = storage;
  }

  public Map<String, Object> upload(Long exceptionId, MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    ensureException(principal.getTenantId(), exceptionId);
    if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "附件不能为空");
    if (file.getSize() > MAX_SIZE) throw new BusinessException(ErrorCode.ATTACHMENT_TOO_LARGE, "附件不能超过 10MB");
    String objectKey = principal.getTenantId() + "/" + exceptionId + "/" + UUID.randomUUID().toString();
    try {
      storage.put(objectKey, file.getBytes());
      jdbcTemplate.update("insert into exception_attachment (tenant_id, exception_case_id, file_name, content_type, file_size, file_content, storage_backend, object_key, uploaded_by) values (?, ?, ?, ?, ?, ?, 'local', ?, ?)", principal.getTenantId(), exceptionId, file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename(), file.getContentType() == null ? "application/octet-stream" : file.getContentType(), file.getSize(), new byte[0], objectKey, principal.getUserId());
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.SYSTEM_ERROR, "附件上传失败，请稍后重试");
    }
    auditService.record("UPLOAD_EXCEPTION_ATTACHMENT", "exception_case", String.valueOf(exceptionId), "file_size=" + file.getSize());
    return jdbcTemplate.queryForMap("select id, exception_case_id, file_name, content_type, file_size, uploaded_by, created_at from exception_attachment where tenant_id = ? and exception_case_id = ? order by id desc limit 1", principal.getTenantId(), exceptionId);
  }

  public List<Map<String, Object>> list(Long exceptionId) {
    AuthPrincipal principal = requireAuth();
    ensureException(principal.getTenantId(), exceptionId);
    return jdbcTemplate.queryForList("select id, exception_case_id, file_name, content_type, file_size, uploaded_by, created_at from exception_attachment where tenant_id = ? and exception_case_id = ? and deleted_at is null order by id desc", principal.getTenantId(), exceptionId);
  }

  public Map<String, Object> download(Long attachmentId) {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("select id, exception_case_id, file_name, content_type, file_size, file_content, storage_backend, object_key from exception_attachment where id = ? and tenant_id = ? and deleted_at is null", attachmentId, principal.getTenantId());
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "附件不存在");
    auditService.record("DOWNLOAD_EXCEPTION_ATTACHMENT", "exception_attachment", String.valueOf(attachmentId), "");
    return rows.get(0);
  }

  public void delete(Long attachmentId) {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("select id, object_key, storage_backend from exception_attachment where id = ? and tenant_id = ? and deleted_at is null", attachmentId, principal.getTenantId());
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "附件不存在");
    Map<String, Object> row = rows.get(0);
    try {
      if (row.get("object_key") != null && "local".equals(row.get("storage_backend"))) storage.delete(String.valueOf(row.get("object_key")));
      jdbcTemplate.update("update exception_attachment set deleted_at = current_timestamp where id = ? and tenant_id = ? and deleted_at is null", attachmentId, principal.getTenantId());
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.SYSTEM_ERROR, "附件删除失败，请稍后重试");
    }
    auditService.record("DELETE_EXCEPTION_ATTACHMENT", "exception_attachment", String.valueOf(attachmentId), "");
  }

  public byte[] content(Map<String, Object> row) {
    try {
      if (row.get("object_key") != null && "local".equals(row.get("storage_backend"))) return storage.get(String.valueOf(row.get("object_key")));
      Object value = row.get("file_content");
      if (value instanceof byte[]) return (byte[]) value;
      if (value instanceof java.sql.Blob) {
        java.sql.Blob blob = (java.sql.Blob) value;
        return blob.getBytes(1, (int) blob.length());
      }
      return new byte[0];
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取附件内容失败，请稍后重试");
    }
  }

  private void ensureException(Long tenantId, Long exceptionId) { Integer count = jdbcTemplate.queryForObject("select count(*) from exception_case where id = ? and tenant_id = ? and deleted_at is null", Integer.class, exceptionId, tenantId); if (count == null || count == 0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "异常事项不存在"); }
  private AuthPrincipal requireAuth() { AuthPrincipal principal = AuthContext.get(); if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录"); return principal; }
}
