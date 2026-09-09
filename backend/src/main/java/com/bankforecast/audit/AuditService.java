package com.bankforecast.audit;

import com.bankforecast.common.TraceIdHolder;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

  private final JdbcTemplate jdbcTemplate;

  public AuditService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void record(String action, String targetType, String targetId, String detail) {
    AuthPrincipal principal = AuthContext.get();
    Long tenantId = principal == null ? 0L : principal.getTenantId();
    Long userId = principal == null ? null : principal.getUserId();
    jdbcTemplate.update(
        "insert into audit_log (tenant_id, user_id, action, target_type, target_id, trace_id, detail) values (?, ?, ?, ?, ?, ?, ?)",
        tenantId,
        userId,
        action,
        targetType,
        targetId,
        TraceIdHolder.next(),
        detail);
  }
}
