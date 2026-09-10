package com.bankforecast.attachment;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {
  private static final long MAX_SIZE = 10 * 1024 * 1024;
  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;

  public AttachmentService(JdbcTemplate jdbcTemplate, AuditService auditService) { this.jdbcTemplate = jdbcTemplate; this.auditService = auditService; }

  public Map<String, Object> upload(Long exceptionId, MultipartFile file) {
    AuthPrincipal principal = requireAuth();
    ensureException(principal.getTenantId(), exceptionId);
    if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "附件不能为空");
    if (file.getSize() > MAX_SIZE) throw new BusinessException(ErrorCode.ATTACHMENT_TOO_LARGE, "附件不能超过 10MB");
    try {
      jdbcTemplate.update("insert into exception_attachment (tenant_id, exception_case_id, file_name, content_type, file_size, file_content, uploaded_by) values (?, ?, ?, ?, ?, ?, ?)", principal.getTenantId(), exceptionId, file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename(), file.getContentType() == null ? "application/octet-stream" : file.getContentType(), file.getSize(), file.getBytes(), principal.getUserId());
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
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("select id, exception_case_id, file_name, content_type, file_size, file_content from exception_attachment where id = ? and tenant_id = ? and deleted_at is null", attachmentId, principal.getTenantId());
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "附件不存在");
    auditService.record("DOWNLOAD_EXCEPTION_ATTACHMENT", "exception_attachment", String.valueOf(attachmentId), "");
    return rows.get(0);
  }

  private void ensureException(Long tenantId, Long exceptionId) { Integer count = jdbcTemplate.queryForObject("select count(*) from exception_case where id = ? and tenant_id = ? and deleted_at is null", Integer.class, exceptionId, tenantId); if (count == null || count == 0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "异常事项不存在"); }
  private AuthPrincipal requireAuth() { AuthPrincipal principal = AuthContext.get(); if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录"); return principal; }
}
