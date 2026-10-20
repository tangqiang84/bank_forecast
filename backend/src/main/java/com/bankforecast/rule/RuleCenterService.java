package com.bankforecast.rule;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleCenterService {

  public static final String CONFIG_SCAN_WINDOW = "match_scan_window_days";
  public static final String CONFIG_EXACT_WINDOW = "match_exact_window_days";
  public static final String CONFIG_SUGGEST_WINDOW = "match_suggest_window_days";
  public static final String CONFIG_INDUSTRY = "industry_template";
  public static final int DEFAULT_SCAN_WINDOW = 30;
  public static final int DEFAULT_EXACT_WINDOW = 7;
  public static final int DEFAULT_SUGGEST_WINDOW = 14;
  public static final String DEFAULT_INDUSTRY = "it_software";

  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;

  public RuleCenterService(JdbcTemplate jdbcTemplate, AuditService auditService) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
  }

  public List<Map<String, Object>> versions(Long tenantId, String ruleCode) {
    requireRule(ruleCode);
    return jdbcTemplate.queryForList(
        "select id, rule_code, threshold, penalty, max_penalty, enabled, version_no, change_source, remark, updated_by, created_at "
            + "from project_risk_rule_version where tenant_id = ? and rule_code = ? order by version_no desc",
        tenantId, ruleCode);
  }

  @Transactional
  public Map<String, Object> updateRule(Long tenantId, Long userId, String ruleCode, BigDecimal threshold,
      BigDecimal penalty, BigDecimal maxPenalty, boolean enabled, String changeSource, String remark) {
    requireRule(ruleCode);
    if (threshold.compareTo(BigDecimal.ZERO) < 0 || penalty.compareTo(BigDecimal.ZERO) < 0
        || (maxPenalty != null && maxPenalty.compareTo(BigDecimal.ZERO) < 0)) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "风险规则参数不能为负数");
    }
    ensureDefaultRules(tenantId);
    ensureBaselineVersion(tenantId, ruleCode, userId);
    jdbcTemplate.update("update project_risk_rule set threshold = ?, penalty = ?, max_penalty = ?, enabled = ?, updated_by = ?, updated_at = current_timestamp where tenant_id = ? and rule_code = ?",
        threshold, penalty, maxPenalty, enabled, userId, tenantId, ruleCode);
    writeVersion(tenantId, ruleCode, threshold, penalty, maxPenalty, enabled, changeSource, remark, userId);
    auditService.record("UPDATE_PROJECT_RISK_RULE", "project_risk_rule", ruleCode,
        "threshold=" + threshold + ", penalty=" + penalty + ", enabled=" + enabled + ", source=" + changeSource);
    return jdbcTemplate.queryForMap(
        "select id, rule_code, threshold, penalty, max_penalty, enabled, updated_at from project_risk_rule where tenant_id = ? and rule_code = ?",
        tenantId, ruleCode);
  }

  @Transactional
  public Map<String, Object> rollback(Long tenantId, Long userId, String ruleCode, int versionNo) {
    requireRule(ruleCode);
    List<Map<String, Object>> versions = jdbcTemplate.queryForList(
        "select threshold, penalty, max_penalty, enabled from project_risk_rule_version where tenant_id = ? and rule_code = ? and version_no = ?",
        tenantId, ruleCode, versionNo);
    if (versions.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "规则版本不存在");
    Map<String, Object> version = versions.get(0);
    Map<String, Object> restored = updateRule(tenantId, userId, ruleCode,
        decimal(version.get("threshold")), decimal(version.get("penalty")),
        version.get("max_penalty") == null ? null : decimal(version.get("max_penalty")),
        Boolean.parseBoolean(String.valueOf(version.get("enabled"))),
        "rollback", "回滚到版本 v" + versionNo);
    auditService.record("ROLLBACK_PROJECT_RISK_RULE", "project_risk_rule", ruleCode, "rollback_to=v" + versionNo);
    return restored;
  }

  public Map<String, Object> config(Long tenantId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put(CONFIG_SCAN_WINDOW, configInt(tenantId, CONFIG_SCAN_WINDOW, DEFAULT_SCAN_WINDOW));
    data.put(CONFIG_EXACT_WINDOW, configInt(tenantId, CONFIG_EXACT_WINDOW, DEFAULT_EXACT_WINDOW));
    data.put(CONFIG_SUGGEST_WINDOW, configInt(tenantId, CONFIG_SUGGEST_WINDOW, DEFAULT_SUGGEST_WINDOW));
    data.put(CONFIG_INDUSTRY, configText(tenantId, CONFIG_INDUSTRY, DEFAULT_INDUSTRY));
    return data;
  }

  @Transactional
  public Map<String, Object> updateConfig(Long tenantId, Long userId, Map<String, Object> request) {
    int scan = configValue(request, CONFIG_SCAN_WINDOW, configInt(tenantId, CONFIG_SCAN_WINDOW, DEFAULT_SCAN_WINDOW));
    int exact = configValue(request, CONFIG_EXACT_WINDOW, configInt(tenantId, CONFIG_EXACT_WINDOW, DEFAULT_EXACT_WINDOW));
    int suggest = configValue(request, CONFIG_SUGGEST_WINDOW, configInt(tenantId, CONFIG_SUGGEST_WINDOW, DEFAULT_SUGGEST_WINDOW));
    for (int value : new int[] {scan, exact, suggest}) {
      if (value < 1 || value > 90) {
        throw new BusinessException(ErrorCode.PARAM_ERROR, "匹配窗口天数必须在 1 到 90 之间");
      }
    }
    if (exact > scan || suggest > scan) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "精确和推荐窗口不能超过扫描窗口");
    }
    putConfig(tenantId, CONFIG_SCAN_WINDOW, String.valueOf(scan), userId);
    putConfig(tenantId, CONFIG_EXACT_WINDOW, String.valueOf(exact), userId);
    putConfig(tenantId, CONFIG_SUGGEST_WINDOW, String.valueOf(suggest), userId);
    auditService.record("UPDATE_RULE_CONFIG", "tenant_rule_config", "match_window",
        "scan=" + scan + ", exact=" + exact + ", suggest=" + suggest);
    return config(tenantId);
  }

  @Transactional
  public Map<String, Object> applyIndustryTemplate(Long tenantId, Long userId, String industry) {
    String code = industry == null || industry.trim().isEmpty() ? DEFAULT_INDUSTRY : industry.trim();
    Object[][] preset = industryPreset(code);
    if (preset == null) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "暂不支持的行业模板，当前仅支持 it_software、manufacturing");
    }
    for (Object[] rule : preset) {
      updateRule(tenantId, userId, String.valueOf(rule[0]),
          new BigDecimal(String.valueOf(rule[1])), new BigDecimal(String.valueOf(rule[2])),
          rule[3] == null ? null : new BigDecimal(String.valueOf(rule[3])), true,
          "template", "应用行业模板 " + code);
    }
    putConfig(tenantId, CONFIG_INDUSTRY, code, userId);
    auditService.record("APPLY_INDUSTRY_TEMPLATE", "tenant_rule_config", CONFIG_INDUSTRY, "industry=" + code);
    Map<String, Object> result = config(tenantId);
    result.put("applied", code);
    return result;
  }

  public int[] matchWindows(Long tenantId) {
    return new int[] {
        configInt(tenantId, CONFIG_SCAN_WINDOW, DEFAULT_SCAN_WINDOW),
        configInt(tenantId, CONFIG_EXACT_WINDOW, DEFAULT_EXACT_WINDOW),
        configInt(tenantId, CONFIG_SUGGEST_WINDOW, DEFAULT_SUGGEST_WINDOW)};
  }

  /** 行业模板阈值包：验收/回款周期与垫资特征不同，逾期与异常容忍度不同。 */
  private static Object[][] industryPreset(String code) {
    if ("it_software".equals(code)) {
      // IT 软件与信息服务：验收/回款周期长，逾期容忍度低
      return new Object[][] {
          {"overdue_exists", "0", "30", null},
          {"overdue_ratio_high", "0.3", "20", null},
          {"paid_rate_low", "0.5", "30", null},
          {"paid_rate_mid", "0.8", "15", null},
          {"open_exception_count", "1", "10", "30"},
          {"negative_cashflow", "0", "15", null}};
    }
    if ("manufacturing".equals(code)) {
      // 制造业：生产与回款周期更长、质保金常见、垫资压力大，逾期/异常容忍略宽，现金流扣分更重
      return new Object[][] {
          {"overdue_exists", "0", "30", null},
          {"overdue_ratio_high", "0.4", "15", null},
          {"paid_rate_low", "0.4", "30", null},
          {"paid_rate_mid", "0.7", "15", null},
          {"open_exception_count", "2", "10", "30"},
          {"negative_cashflow", "0", "20", null}};
    }
    return null;
  }

  private void ensureBaselineVersion(Long tenantId, String ruleCode, Long userId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from project_risk_rule_version where tenant_id = ? and rule_code = ?",
        Integer.class, tenantId, ruleCode);
    if (count != null && count > 0) return;
    List<Map<String, Object>> current = jdbcTemplate.queryForList(
        "select threshold, penalty, max_penalty, enabled from project_risk_rule where tenant_id = ? and rule_code = ?",
        tenantId, ruleCode);
    if (current.isEmpty()) return;
    Map<String, Object> rule = current.get(0);
    writeVersion(tenantId, ruleCode, decimal(rule.get("threshold")), decimal(rule.get("penalty")),
        rule.get("max_penalty") == null ? null : decimal(rule.get("max_penalty")),
        Boolean.parseBoolean(String.valueOf(rule.get("enabled"))), "baseline", "规则基线", userId);
  }

  private void writeVersion(Long tenantId, String ruleCode, BigDecimal threshold, BigDecimal penalty,
      BigDecimal maxPenalty, boolean enabled, String changeSource, String remark, Long userId) {
    Integer max = jdbcTemplate.queryForObject(
        "select coalesce(max(version_no), 0) from project_risk_rule_version where tenant_id = ? and rule_code = ?",
        Integer.class, tenantId, ruleCode);
    jdbcTemplate.update("insert into project_risk_rule_version (tenant_id, rule_code, threshold, penalty, max_penalty, enabled, version_no, change_source, remark, updated_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        tenantId, ruleCode, threshold, penalty, maxPenalty, enabled,
        (max == null ? 0 : max) + 1, changeSource, remark, userId);
  }

  private void ensureDefaultRules(Long tenantId) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from project_risk_rule where tenant_id = ?", Integer.class, tenantId);
    if (count != null && count > 0) return;
    Object[][] defaults = {{"overdue_exists", "0", "30", null}, {"overdue_ratio_high", "0.5", "15", null},
        {"paid_rate_low", "0.5", "30", null}, {"paid_rate_mid", "0.8", "15", null},
        {"open_exception_count", "1", "10", "30"}, {"negative_cashflow", "0", "15", null}};
    for (Object[] item : defaults) {
      jdbcTemplate.update("insert into project_risk_rule (tenant_id, rule_code, threshold, penalty, max_penalty) values (?, ?, ?, ?, ?)",
          tenantId, item[0], new BigDecimal(String.valueOf(item[1])), new BigDecimal(String.valueOf(item[2])), item[3] == null ? null : new BigDecimal(String.valueOf(item[3])));
    }
  }

  private void requireRule(String ruleCode) {
    if (!Arrays.asList("overdue_exists", "overdue_ratio_high", "paid_rate_low", "paid_rate_mid",
        "open_exception_count", "negative_cashflow").contains(ruleCode)) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的项目风险规则");
    }
  }

  private int configInt(Long tenantId, String key, int defaultValue) {
    String value = configText(tenantId, key, null);
    if (value == null) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private String configText(Long tenantId, String key, String defaultValue) {
    List<String> values = jdbcTemplate.queryForList(
        "select config_value from tenant_rule_config where tenant_id = ? and config_key = ?",
        String.class, tenantId, key);
    return values.isEmpty() ? defaultValue : values.get(0);
  }

  private int configValue(Map<String, Object> request, String key, int current) {
    Object value = request == null ? null : request.get(key);
    if (value == null || String.valueOf(value).trim().isEmpty()) return current;
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException ex) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, key + " 必须是整数天数");
    }
  }

  private void putConfig(Long tenantId, String key, String value, Long userId) {
    int updated = jdbcTemplate.update(
        "update tenant_rule_config set config_value = ?, updated_by = ?, updated_at = current_timestamp where tenant_id = ? and config_key = ?",
        value, userId, tenantId, key);
    if (updated == 0) {
      jdbcTemplate.update("insert into tenant_rule_config (tenant_id, config_key, config_value, updated_by) values (?, ?, ?, ?)",
          tenantId, key, value, userId);
    }
  }

  private BigDecimal decimal(Object value) { return new BigDecimal(String.valueOf(value)); }

  /** 异常按类型自动分派配置（P3-3）：key = exception_assign.<type>，value = 用户 ID。 */
  public Map<String, Object> exceptionAssignment(Long tenantId) {
    Map<String, String> configured = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(
        "select config_key, config_value from tenant_rule_config where tenant_id = ? and config_key like 'exception_assign.%'",
        tenantId)) {
      configured.put(String.valueOf(row.get("config_key")).substring("exception_assign.".length()),
          String.valueOf(row.get("config_value")));
    }
    List<Map<String, Object>> items = new ArrayList<>();
    for (String type : com.bankforecast.matching.ExceptionAutoAssignService.EXCEPTION_TYPES) {
      String userId = configured.get(type);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("exception_type", type);
      item.put("owner_user_id", userId == null ? null : Long.valueOf(userId));
      item.put("owner_name", userId == null ? null : userDisplayName(tenantId, Long.valueOf(userId)));
      items.add(item);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    return data;
  }

  @Transactional
  public Map<String, Object> updateExceptionAssignment(Long tenantId, Long userId, Map<String, Object> request) {
    Object raw = request == null ? null : request.get("assignments");
    if (!(raw instanceof Map)) throw new BusinessException(ErrorCode.PARAM_ERROR, "assignments 必须是对象");
    List<String> allowed = Arrays.asList(com.bankforecast.matching.ExceptionAutoAssignService.EXCEPTION_TYPES);
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
      String type = String.valueOf(entry.getKey());
      if (!allowed.contains(type)) throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的异常类型：" + type);
      Object value = entry.getValue();
      if (value == null || String.valueOf(value).trim().isEmpty()) {
        jdbcTemplate.update("delete from tenant_rule_config where tenant_id = ? and config_key = ?",
            tenantId, "exception_assign." + type);
        continue;
      }
      Long assigneeId;
      try {
        assigneeId = Long.valueOf(String.valueOf(value).trim());
      } catch (NumberFormatException ex) {
        throw new BusinessException(ErrorCode.PARAM_ERROR, "assignments." + type + " 必须是用户 ID");
      }
      Integer active = jdbcTemplate.queryForObject(
          "select count(*) from user_account where id = ? and tenant_id = ? and status = 'active'",
          Integer.class, assigneeId, tenantId);
      if (active == null || active == 0) {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "处理人不存在或已停用：" + type);
      }
      putConfig(tenantId, "exception_assign." + type, String.valueOf(assigneeId), userId);
    }
    auditService.record("UPDATE_EXCEPTION_ASSIGNMENT", "tenant_rule_config", "exception_assign", "");
    return exceptionAssignment(tenantId);
  }

  private String userDisplayName(Long tenantId, Long userId) {
    List<String> names = jdbcTemplate.queryForList(
        "select display_name from user_account where id = ? and tenant_id = ?", String.class, userId, tenantId);
    return names.isEmpty() ? null : names.get(0);
  }
}
