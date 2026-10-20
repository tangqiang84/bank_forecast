package com.bankforecast.matching;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 异常按类型自动分派（P3-3）：tenant_rule_config 中 `exception_assign.<type>` 配置责任人用户 ID，
 * 异常创建时调用 autoAssign；配置缺失或用户无效时保持未分派。
 */
@Service
public class ExceptionAutoAssignService {

  public static final String[] EXCEPTION_TYPES = {
      "unreceived", "partial_receipt", "unknown_receipt",
      "bank_unrecorded", "finance_unmatched", "timing_difference", "fund_gap", "match_review"};
  public static final String CONFIG_PREFIX = "exception_assign.";

  private final JdbcTemplate jdbcTemplate;

  public ExceptionAutoAssignService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean autoAssign(Long tenantId, String exceptionType, Long exceptionId) {
    List<String> values = jdbcTemplate.queryForList(
        "select config_value from tenant_rule_config where tenant_id = ? and config_key = ?",
        String.class, tenantId, CONFIG_PREFIX + exceptionType);
    if (values.isEmpty()) return false;
    Long userId;
    try {
      userId = Long.valueOf(values.get(0).trim());
    } catch (Exception ex) {
      return false;
    }
    Integer active = jdbcTemplate.queryForObject(
        "select count(*) from user_account where id = ? and tenant_id = ? and status = 'active'",
        Integer.class, userId, tenantId);
    if (active == null || active == 0) return false;
    jdbcTemplate.update(
        "update exception_case set owner_user_id = ?, status = case when status = 'new' then 'in_progress' else status end, updated_at = current_timestamp where id = ? and tenant_id = ?",
        userId, exceptionId, tenantId);
    jdbcTemplate.update(
        "insert into exception_action_log (tenant_id, exception_case_id, action_type, action_by, action_text) values (?, ?, 'ASSIGN', null, ?)",
        tenantId, exceptionId, "按类型自动分派给用户 " + userId);
    return true;
  }

  /** 按唯一键查询刚创建的异常并自动分派；返回是否命中配置。 */
  public boolean autoAssignBySource(Long tenantId, String exceptionType, String sourceType, Long sourceId) {
    List<Long> ids = jdbcTemplate.queryForList(
        "select id from exception_case where tenant_id = ? and exception_type = ? and source_type = ? and source_id = ? and deleted_at is null order by id desc limit 1",
        Long.class, tenantId, exceptionType, sourceType, sourceId);
    if (ids.isEmpty()) return false;
    return autoAssign(tenantId, exceptionType, ids.get(0));
  }
}
