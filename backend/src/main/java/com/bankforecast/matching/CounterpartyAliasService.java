package com.bankforecast.matching;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 对手方别名归一（P3-6）：租户级别名 → 标准名称，回款匹配运行时统一应用。 */
@Service
public class CounterpartyAliasService {

  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;

  public CounterpartyAliasService(JdbcTemplate jdbcTemplate, AuditService auditService) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
  }

  public List<Map<String, Object>> list(Long tenantId) {
    return jdbcTemplate.queryForList(
        "select id, alias, canonical_name, created_at, updated_at from counterparty_alias where tenant_id = ? order by id desc",
        tenantId);
  }

  /** 归一化别名 → 标准名称。 */
  public Map<String, String> aliases(Long tenantId) {
    Map<String, String> aliases = new HashMap<>();
    for (Map<String, Object> row : list(tenantId)) {
      String key = CustomerNameNormalizer.normalize(String.valueOf(row.get("alias")));
      if (!key.isEmpty()) aliases.put(key, String.valueOf(row.get("canonical_name")));
    }
    return aliases;
  }

  /** 应用别名：原始名称归一后命中别名表则返回标准名称，否则原样返回。 */
  public static String apply(Map<String, String> aliases, String rawName) {
    if (rawName == null || rawName.trim().isEmpty()) return rawName;
    String canonical = aliases.get(CustomerNameNormalizer.normalize(rawName));
    return canonical == null ? rawName : canonical;
  }

  @Transactional
  public Map<String, Object> create(Long tenantId, Long userId, Map<String, Object> request) {
    String alias = requiredText(request.get("alias"), "alias");
    String canonicalName = requiredText(request.get("canonical_name"), "canonical_name");
    KeyHolder holder = new GeneratedKeyHolder();
    try {
      jdbcTemplate.update(connection -> {
        PreparedStatement ps = connection.prepareStatement(
            "insert into counterparty_alias (tenant_id, alias, canonical_name, created_by) values (?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS);
        ps.setLong(1, tenantId);
        ps.setString(2, alias);
        ps.setString(3, canonicalName);
        ps.setLong(4, userId);
        return ps;
      }, holder);
    } catch (DuplicateKeyException ex) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "该别名已存在，请直接编辑");
    }
    Long id = generatedId(holder);
    auditService.record("CREATE_COUNTERPARTY_ALIAS", "counterparty_alias", String.valueOf(id),
        "alias=" + alias + ", canonical=" + canonicalName);
    return find(tenantId, id);
  }

  @Transactional
  public Map<String, Object> update(Long tenantId, Long id, Map<String, Object> request) {
    find(tenantId, id);
    String alias = requiredText(request.get("alias"), "alias");
    String canonicalName = requiredText(request.get("canonical_name"), "canonical_name");
    try {
      jdbcTemplate.update(
          "update counterparty_alias set alias = ?, canonical_name = ?, updated_at = current_timestamp where id = ? and tenant_id = ?",
          alias, canonicalName, id, tenantId);
    } catch (DuplicateKeyException ex) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "该别名已存在，请直接编辑");
    }
    auditService.record("UPDATE_COUNTERPARTY_ALIAS", "counterparty_alias", String.valueOf(id), "");
    return find(tenantId, id);
  }

  @Transactional
  public Map<String, Object> delete(Long tenantId, Long id) {
    Map<String, Object> existing = find(tenantId, id);
    jdbcTemplate.update("delete from counterparty_alias where id = ? and tenant_id = ?", id, tenantId);
    auditService.record("DELETE_COUNTERPARTY_ALIAS", "counterparty_alias", String.valueOf(id),
        "alias=" + existing.get("alias"));
    return java.util.Collections.singletonMap("deleted", true);
  }

  private Map<String, Object> find(Long tenantId, Long id) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select id, alias, canonical_name, created_at, updated_at from counterparty_alias where id = ? and tenant_id = ?",
        id, tenantId);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "对手方别名不存在");
    return rows.get(0);
  }

  private String requiredText(Object value, String field) {
    String text = value == null ? "" : String.valueOf(value).trim();
    if (text.isEmpty() || text.length() > 256) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 必填且不超过 256 字符");
    }
    return text;
  }

  private Long generatedId(KeyHolder holder) {
    if (holder.getKeys() != null && holder.getKeys().get("id") != null) {
      return ((Number) holder.getKeys().get("id")).longValue();
    }
    return holder.getKey().longValue();
  }

  public Long currentTenantId() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal.getTenantId();
  }

  public Long currentUserId() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal.getUserId();
  }
}
