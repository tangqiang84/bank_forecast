package com.bankforecast.security;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PermissionRepository {

  private final JdbcTemplate jdbcTemplate;

  public PermissionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Set<String> findPermissionCodes(Long tenantId, List<String> roleCodes) {
    if (roleCodes == null || roleCodes.isEmpty()) {
      return Collections.emptySet();
    }
    String placeholders = String.join(", ", Collections.nCopies(roleCodes.size(), "?"));
    Object[] args = new Object[roleCodes.size() + 1];
    args[0] = tenantId;
    for (int i = 0; i < roleCodes.size(); i++) {
      args[i + 1] = roleCodes.get(i);
    }
    List<String> codes = jdbcTemplate.queryForList(
        "select distinct p.permission_code from permission p "
            + "join role_permission rp on rp.permission_id = p.id "
            + "join role r on r.id = rp.role_id "
            + "where rp.tenant_id = ? and r.role_code in (" + placeholders + ") "
            + "and r.status = 'active' and p.status = 'active'",
        String.class,
        args);
    return new HashSet<>(codes);
  }

  public List<Long> findScopedProjectIds(Long tenantId, Long userId) {
    return jdbcTemplate.queryForList(
        "select project_id from user_project_scope where tenant_id = ? and user_id = ?",
        Long.class,
        tenantId,
        userId);
  }
}
