package com.bankforecast.service;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {
  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;

  public ProjectService(JdbcTemplate jdbcTemplate, AuditService auditService) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
  }

  public Map<String, Object> list(Long tenantId, int page, int pageSize, String projectNo, String customerName, String projectStatus, List<Long> scopedProjectIds) {
    int safePage = Math.max(page, 1), safeSize = Math.min(Math.max(pageSize, 1), 100), offset = (safePage - 1) * safeSize;
    String filter = " where p.tenant_id = ? and p.deleted_at is null and (? is null or p.project_no = ?) and (? is null or p.customer_name like ?) and (? is null or p.project_status = ?)";
    String customerLike = blankToNull(customerName) == null ? null : "%" + customerName.trim() + "%";
    Object[] args = {tenantId, blankToNull(projectNo), blankToNull(projectNo), customerLike, customerLike, blankToNull(projectStatus), blankToNull(projectStatus)};
    if (scopedProjectIds != null) {
      if (scopedProjectIds.isEmpty()) {
        filter += " and 1 = 0";
      } else {
        String placeholders = String.join(", ", Collections.nCopies(scopedProjectIds.size(), "?"));
        filter += " and p.id in (" + placeholders + ")";
        args = append(args, scopedProjectIds.toArray());
      }
    }
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("select p.id, p.project_no, p.project_name, p.customer_name, p.project_manager, p.project_status, coalesce((select sum(c.contract_amount) from contract c where c.project_id = p.id and c.deleted_at is null), 0) as contract_amount, coalesce((select sum(r.plan_amount) from contract_receivable_plan r where r.project_id = p.id and r.deleted_at is null), 0) as receivable_amount, coalesce((select sum(r.paid_amount) from contract_receivable_plan r where r.project_id = p.id and r.deleted_at is null), 0) as paid_amount, coalesce((select sum(r.plan_amount - r.paid_amount) from contract_receivable_plan r where r.project_id = p.id and r.status <> 'paid' and r.due_date < current_date and r.deleted_at is null), 0) as overdue_amount, coalesce((select sum(case when bt2.direction = 'income' then a2.allocated_amount when bt2.direction = 'expense' then -a2.allocated_amount else 0 end) from match_result_allocation a2 join match_result mr2 on mr2.id = a2.match_result_id join bank_transaction bt2 on bt2.id = a2.bank_transaction_id where mr2.project_id = p.id and mr2.tenant_id = p.tenant_id and a2.status in ('matched', 'confirmed', 'auto_confirmed', 'manual_confirmed') and mr2.deleted_at is null and a2.deleted_at is null), 0) as cashflow_amount, (select count(*) from contract c where c.project_id = p.id and c.deleted_at is null) as contract_count, (select count(*) from exception_case e where e.tenant_id = p.tenant_id and e.source_type = 'contract_receivable_plan' and exists (select 1 from contract_receivable_plan r where r.id = e.source_id and r.project_id = p.id) and e.status not in ('closed', 'false_positive') and e.deleted_at is null) as exception_count from project p" + filter + " order by p.id desc limit ? offset ?", append(args, safeSize, offset));
    for (Map<String, Object> row : rows) enrichRisk(row, tenantId);
    Map<String, Object> data = new LinkedHashMap<>(); data.put("items", rows); data.put("page", safePage); data.put("page_size", safeSize); data.put("total", jdbcTemplate.queryForObject("select count(*) from project p" + filter, args, Integer.class)); return data;
  }

  public List<Map<String, Object>> riskRules(Long tenantId) { ensureDefaultRules(tenantId); return jdbcTemplate.queryForList("select id, rule_code, threshold, penalty, max_penalty, enabled, updated_at from project_risk_rule where tenant_id = ? order by id", tenantId); }

  public Map<String, Object> updateRiskRule(Long tenantId, Long userId, String ruleCode, Map<String, Object> request) {
    if (!allowedRule(ruleCode)) throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的项目风险规则");
    BigDecimal threshold = decimal(request.get("threshold")), penalty = decimal(request.get("penalty")), maxPenalty = request.get("max_penalty") == null ? null : decimal(request.get("max_penalty"));
    if (threshold.compareTo(BigDecimal.ZERO) < 0 || penalty.compareTo(BigDecimal.ZERO) < 0 || (maxPenalty != null && maxPenalty.compareTo(BigDecimal.ZERO) < 0)) throw new BusinessException(ErrorCode.PARAM_ERROR, "风险规则参数不能为负数");
    boolean enabled = request.get("enabled") == null || Boolean.parseBoolean(String.valueOf(request.get("enabled")));
    ensureDefaultRules(tenantId);
    jdbcTemplate.update("update project_risk_rule set threshold = ?, penalty = ?, max_penalty = ?, enabled = ?, updated_by = ?, updated_at = current_timestamp where tenant_id = ? and rule_code = ?", threshold, penalty, maxPenalty, enabled, userId, tenantId, ruleCode);
    auditService.record("UPDATE_PROJECT_RISK_RULE", "project_risk_rule", ruleCode, request.toString());
    return jdbcTemplate.queryForMap("select id, rule_code, threshold, penalty, max_penalty, enabled, updated_at from project_risk_rule where tenant_id = ? and rule_code = ?", tenantId, ruleCode);
  }

  public Map<String, Object> update(Long tenantId, Long userId, Long id, Map<String, Object> request) {
    String projectName = requiredText(request, "project_name"), customerName = optionalText(request, "customer_name"), projectManager = optionalText(request, "project_manager"), projectStatus = optionalText(request, "project_status");
    if (projectStatus == null) projectStatus = "active";
    if (!Arrays.asList("active", "completed", "paused", "cancelled").contains(projectStatus)) throw new BusinessException(ErrorCode.PARAM_ERROR, "项目状态不合法");
    int count = jdbcTemplate.update("update project set project_name = ?, customer_name = ?, project_manager = ?, project_status = ?, updated_at = current_timestamp where id = ? and tenant_id = ? and deleted_at is null", projectName, customerName, projectManager, projectStatus, id, tenantId);
    if (count == 0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在");
    auditService.record("UPDATE_PROJECT", "project", String.valueOf(id), request.toString());
    return jdbcTemplate.queryForMap("select id, project_no, project_name, customer_name, project_manager, project_status, created_at, updated_at from project where id = ? and tenant_id = ?", id, tenantId);
  }

  public Map<String, Object> batchStatus(Long tenantId, List<Long> ids, String status) {
    if (ids.isEmpty() || status == null || !Arrays.asList("active", "completed", "paused", "cancelled").contains(status)) throw new BusinessException(ErrorCode.PARAM_ERROR, "项目批量更新参数不合法");
    int updated = 0; for (Long id : ids) updated += jdbcTemplate.update("update project set project_status = ?, updated_at = current_timestamp where id = ? and tenant_id = ? and deleted_at is null", status, id, tenantId);
    auditService.record("BATCH_UPDATE_PROJECT_STATUS", "project", ids.toString(), "status=" + status);
    return Collections.<String, Object>singletonMap("updated", updated);
  }

  public Map<String, Object> detail(Long tenantId, Long id, List<Long> scopedProjectIds) {
    List<Map<String, Object>> projects = jdbcTemplate.queryForList("select id, project_no, project_name, customer_name, project_manager, project_status, created_at, updated_at from project where id = ? and tenant_id = ? and deleted_at is null", id, tenantId);
    if (projects.isEmpty() || (scopedProjectIds != null && !scopedProjectIds.contains(id))) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在");
    Map<String, Object> project = projects.get(0);
    Map<String, Object> metrics = jdbcTemplate.queryForMap("select coalesce((select sum(c.contract_amount) from contract c where c.project_id = ? and c.tenant_id = ? and c.deleted_at is null), 0) as contract_amount, coalesce((select sum(r.plan_amount) from contract_receivable_plan r where r.project_id = ? and r.tenant_id = ? and r.deleted_at is null), 0) as receivable_amount, coalesce((select sum(r.paid_amount) from contract_receivable_plan r where r.project_id = ? and r.tenant_id = ? and r.deleted_at is null), 0) as paid_amount, coalesce((select sum(case when r.status <> 'paid' and r.due_date < current_date then r.plan_amount - r.paid_amount else 0 end) from contract_receivable_plan r where r.project_id = ? and r.tenant_id = ? and r.deleted_at is null), 0) as overdue_amount", id, tenantId, id, tenantId, id, tenantId, id, tenantId);
    Integer exceptions = jdbcTemplate.queryForObject("select count(*) from exception_case e where e.tenant_id = ? and e.source_type = 'contract_receivable_plan' and exists (select 1 from contract_receivable_plan r where r.id = e.source_id and r.project_id = ?) and e.status not in ('closed', 'false_positive') and e.deleted_at is null", Integer.class, tenantId, id);
    Map<String, Object> summary = new LinkedHashMap<>(metrics); summary.put("exception_count", exceptions); summary.put("cashflow_amount", jdbcTemplate.queryForObject("select coalesce(sum(case when bt.direction = 'income' then a.allocated_amount when bt.direction = 'expense' then -a.allocated_amount else 0 end), 0) from match_result_allocation a join match_result mr on mr.id = a.match_result_id join bank_transaction bt on bt.id = a.bank_transaction_id where mr.project_id = ? and mr.tenant_id = ? and a.status in ('matched', 'confirmed', 'auto_confirmed', 'manual_confirmed') and mr.deleted_at is null and a.deleted_at is null", BigDecimal.class, id, tenantId)); enrichRisk(summary, tenantId);
    Map<String, Object> data = new LinkedHashMap<>(); data.put("project", project); data.put("summary", summary); data.put("contracts", jdbcTemplate.queryForList("select id, contract_no, contract_name, customer_name, contract_amount, status from contract where project_id = ? and tenant_id = ? and deleted_at is null order by id desc", id, tenantId)); data.put("receivables", jdbcTemplate.queryForList("select id, contract_id, node_name, node_type, due_date, plan_amount, paid_amount, status from contract_receivable_plan where project_id = ? and tenant_id = ? and deleted_at is null order by due_date, id", id, tenantId)); data.put("transactions", jdbcTemplate.queryForList("select distinct bt.id, bt.transaction_no, bt.transaction_date, bt.direction, bt.amount, bt.counterparty_name, bt.summary, bt.match_status from bank_transaction bt join match_result mr on mr.bank_transaction_id = bt.id where mr.project_id = ? and mr.tenant_id = ? and mr.deleted_at is null order by bt.transaction_date desc, bt.id desc", id, tenantId)); data.put("exceptions", jdbcTemplate.queryForList("select e.id, e.exception_no, e.exception_type, e.title, e.status, e.severity, e.due_date from exception_case e where e.tenant_id = ? and e.source_type = 'contract_receivable_plan' and exists (select 1 from contract_receivable_plan r where r.id = e.source_id and r.project_id = ?) and e.deleted_at is null order by e.id desc", tenantId, id)); return data;
  }

  private void enrichRisk(Map<String, Object> row, Long tenantId) { ensureDefaultRules(tenantId); BigDecimal receivable = decimal(row.get("receivable_amount")), paid = decimal(row.get("paid_amount")), overdue = decimal(row.get("overdue_amount")), cashflow = decimal(row.get("cashflow_amount")); int exceptionCount = number(row.get("exception_count")); BigDecimal paidRate = receivable.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : paid.divide(receivable, 4, RoundingMode.HALF_UP), overdueRate = receivable.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : overdue.divide(receivable, 4, RoundingMode.HALF_UP); int riskScore = 100; List<Map<String, Object>> rules = jdbcTemplate.queryForList("select rule_code, threshold, penalty, max_penalty, enabled from project_risk_rule where tenant_id = ? and enabled = true", tenantId); Map<String, Object> factors = new LinkedHashMap<>(); List<String> risks = new ArrayList<>(); for (Map<String, Object> rule : rules) { String code = String.valueOf(rule.get("rule_code")); BigDecimal value = ruleValue(code, overdue, overdueRate, paidRate, cashflow, exceptionCount), threshold = decimal(rule.get("threshold")); boolean hit = "open_exception_count".equals(code) ? value.compareTo(threshold) >= 0 : value.compareTo(threshold) < 0; if ("overdue_exists".equals(code) || "overdue_ratio_high".equals(code) || "negative_cashflow".equals(code)) hit = value.compareTo(threshold) > 0; BigDecimal deduction = hit ? decimal(rule.get("penalty")) : BigDecimal.ZERO; if ("open_exception_count".equals(code) && hit) deduction = deduction.multiply(BigDecimal.valueOf(exceptionCount)); if (rule.get("max_penalty") != null && deduction.compareTo(decimal(rule.get("max_penalty"))) > 0) deduction = decimal(rule.get("max_penalty")); riskScore -= deduction.intValue(); Map<String, Object> factor = new LinkedHashMap<>(); factor.put("value", value); factor.put("threshold", threshold); factor.put("deduction", deduction); factor.put("triggered", hit); factors.put(code, factor); if (hit) risks.add(ruleTitle(code)); } riskScore = Math.max(0, riskScore); row.put("paid_rate", paidRate); row.put("risk_score", riskScore); row.put("risk_level", riskScore >= 80 ? "healthy" : riskScore >= 60 ? "warning" : "danger"); row.put("cashflow_amount", cashflow); row.put("overdue_rate", overdueRate); row.put("risk_factors", factors); row.put("risk_items", risks); }
  private BigDecimal ruleValue(String code, BigDecimal overdue, BigDecimal overdueRate, BigDecimal paidRate, BigDecimal cashflow, int exceptions) { if ("overdue_exists".equals(code)) return overdue; if ("overdue_ratio_high".equals(code)) return overdueRate; if ("paid_rate_low".equals(code) || "paid_rate_mid".equals(code)) return paidRate; if ("negative_cashflow".equals(code)) return cashflow; return BigDecimal.valueOf(exceptions); }
  private String ruleTitle(String code) { if ("overdue_exists".equals(code)) return "存在逾期应收"; if ("overdue_ratio_high".equals(code)) return "逾期金额占比较高"; if ("paid_rate_low".equals(code)) return "回款率低于50%"; if ("paid_rate_mid".equals(code)) return "回款率低于80%"; if ("negative_cashflow".equals(code)) return "项目关联流水净现金流为负"; return "存在待处理异常"; }
  private void ensureDefaultRules(Long tenantId) { Integer count = jdbcTemplate.queryForObject("select count(*) from project_risk_rule where tenant_id = ?", Integer.class, tenantId); if (count != null && count > 0) return; Object[][] defaults = {{"overdue_exists", "0", "30", null}, {"overdue_ratio_high", "0.5", "15", null}, {"paid_rate_low", "0.5", "30", null}, {"paid_rate_mid", "0.8", "15", null}, {"open_exception_count", "1", "10", "30"}, {"negative_cashflow", "0", "15", null}}; for (Object[] item : defaults) jdbcTemplate.update("insert into project_risk_rule (tenant_id, rule_code, threshold, penalty, max_penalty) values (?, ?, ?, ?, ?)", tenantId, item[0], new BigDecimal(String.valueOf(item[1])), new BigDecimal(String.valueOf(item[2])), item[3] == null ? null : new BigDecimal(String.valueOf(item[3]))); }
  private boolean allowedRule(String code) { return Arrays.asList("overdue_exists", "overdue_ratio_high", "paid_rate_low", "paid_rate_mid", "open_exception_count", "negative_cashflow").contains(code); }
  private String requiredText(Map<String, Object> request, String field) { String value = optionalText(request, field); if (value == null) throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 不能为空"); return value; }
  private String optionalText(Map<String, Object> request, String field) { Object value = request == null ? null : request.get(field); return value == null || String.valueOf(value).trim().isEmpty() ? null : String.valueOf(value).trim(); }
  private int number(Object value) { return value == null ? 0 : ((Number) value).intValue(); }
  private BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
  private String blankToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
  private Object[] append(Object[] values, Object... extra) { Object[] result = new Object[values.length + extra.length]; System.arraycopy(values, 0, result, 0, values.length); System.arraycopy(extra, 0, result, values.length, extra.length); return result; }
}
