package com.bankforecast.rule;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.matching.ExceptionAutoAssignService;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 行业预警规则（P3-8，PRD §8.3 五项）。手动触发执行，幂等（按 source 唯一键不重复生成）。
 * 阈值默认值：验收后 30 天、质保期 90 天、同客户连续 2 个项目、工资类支出近 3 个月月均 × 2 个月；
 * 可通过 tenant_rule_config 覆盖：alert_acceptance_days / alert_warranty_days / alert_payroll_months。
 */
@Service
public class IndustryAlertService {

  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;
  private final ExceptionAutoAssignService autoAssignService;

  public IndustryAlertService(JdbcTemplate jdbcTemplate, AuditService auditService,
      ExceptionAutoAssignService autoAssignService) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
    this.autoAssignService = autoAssignService;
  }

  public Map<String, Object> run() {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    int acceptanceDays = configInt(tenantId, "alert_acceptance_days", 30);
    int warrantyDays = configInt(tenantId, "alert_warranty_days", 90);
    int payrollMonths = configInt(tenantId, "alert_payroll_months", 3);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("acceptance_overdue", alertAcceptanceOverdue(tenantId, acceptanceDays));
    result.put("warranty_overdue", alertWarrantyOverdue(tenantId, warrantyDays));
    result.put("customer_risk", alertCustomerStreak(tenantId));
    result.put("payroll_gap", alertPayrollGap(tenantId, payrollMonths));
    result.put("project_cashflow_risk", alertProjectCashflow(tenantId));
    auditService.record("RUN_INDUSTRY_ALERTS", "tenant_rule_config", "industry_alerts",
        "acceptance=" + result.get("acceptance_overdue") + ",warranty=" + result.get("warranty_overdue")
            + ",customer=" + result.get("customer_risk") + ",payroll=" + result.get("payroll_gap")
            + ",cashflow=" + result.get("project_cashflow_risk"));
    return result;
  }

  /** §8.3-1 项目验收后 30 天（可配）未收到验收款/尾款。 */
  private int alertAcceptanceOverdue(Long tenantId, int days) {
    LocalDate cutoff = LocalDate.now().minusDays(days);
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select p.id as plan_id, p.node_name, p.plan_amount, p.paid_amount, p.due_date, pj.project_name, pj.acceptance_date, c.contract_no "
            + "from contract_receivable_plan p "
            + "join project pj on pj.id = p.project_id "
            + "join contract c on c.id = p.contract_id "
            + "where p.tenant_id = ? and p.deleted_at is null and p.status <> 'paid' "
            + "and (p.node_type = 'acceptance' or p.node_name like '%验收%' or p.node_name like '%尾款%') "
            + "and pj.deleted_at is null and pj.acceptance_date is not null and pj.acceptance_date <= ?",
        tenantId, Date.valueOf(cutoff));
    int created = 0;
    for (Map<String, Object> row : rows) {
      if (createException(tenantId, "acceptance_overdue", "contract_receivable_plan", number(row.get("plan_id")),
          "验收款逾期：" + row.get("project_name"),
          "项目已于 " + row.get("acceptance_date") + " 验收，超过 " + days + " 天节点「" + row.get("node_name")
              + "」（合同 " + row.get("contract_no") + "）仍未收讫，应收 " + row.get("plan_amount")
              + "，已收 " + row.get("paid_amount"),
          "high", sqlDate(row.get("due_date")))) created++;
    }
    return created;
  }

  /** §8.3-2 质保期结束（验收 + 90 天，可配）后质保金未收。 */
  private int alertWarrantyOverdue(Long tenantId, int warrantyDays) {
    LocalDate cutoff = LocalDate.now().minusDays(warrantyDays);
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select p.id as plan_id, p.node_name, p.plan_amount, p.paid_amount, pj.project_name, pj.acceptance_date "
            + "from contract_receivable_plan p "
            + "join project pj on pj.id = p.project_id "
            + "where p.tenant_id = ? and p.deleted_at is null and p.status <> 'paid' "
            + "and p.node_name like '%质保%' "
            + "and pj.deleted_at is null and pj.acceptance_date is not null and pj.acceptance_date <= ?",
        tenantId, Date.valueOf(cutoff));
    int created = 0;
    for (Map<String, Object> row : rows) {
      if (createException(tenantId, "warranty_overdue", "contract_receivable_plan", number(row.get("plan_id")),
          "质保金逾期：" + row.get("project_name"),
          "项目于 " + row.get("acceptance_date") + " 验收，已过 " + warrantyDays + " 天质保期，质保金节点「"
              + row.get("node_name") + "」仍未收讫，应收 " + row.get("plan_amount"),
          "high", null)) created++;
    }
    return created;
  }

  /** §8.3-3 同一客户 2 个及以上项目存在逾期未收节点，标记客户风险。 */
  private int alertCustomerStreak(Long tenantId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select c.customer_name, count(distinct p.project_id) as overdue_projects, min(c.id) as contract_id "
            + "from contract_receivable_plan p "
            + "join contract c on c.id = p.contract_id "
            + "where p.tenant_id = ? and p.deleted_at is null and p.status <> 'paid' and p.due_date < current_date "
            + "and c.deleted_at is null "
            + "group by c.customer_name having count(distinct p.project_id) >= 2",
        tenantId);
    int created = 0;
    for (Map<String, Object> row : rows) {
      if (createException(tenantId, "customer_risk", "contract", number(row.get("contract_id")),
          "客户回款风险：" + row.get("customer_name"),
          "客户「" + row.get("customer_name") + "」有 " + row.get("overdue_projects") + " 个项目存在逾期未收节点，请评估客户信用并跟进催收",
          "high", null)) created++;
    }
    return created;
  }

  /** §8.3-4 可用资金低于未来 2 个月工资/社保/报销类预测支出（近 N 个月月均 × 2）。 */
  private int alertPayrollGap(Long tenantId, int payrollMonths) {
    BigDecimal payrollSpend = jdbcTemplate.queryForObject(
        "select coalesce(sum(amount), 0) from bank_transaction where tenant_id = ? and deleted_at is null "
            + "and direction = 'expense' and transaction_date >= ? "
            + "and (summary like '%工资%' or summary like '%社保%' or summary like '%公积金%' or summary like '%报销%' or summary like '%差旅%')",
        BigDecimal.class, tenantId, Date.valueOf(LocalDate.now().minusMonths(payrollMonths)));
    BigDecimal monthlyAvg = payrollSpend == null ? BigDecimal.ZERO
        : payrollSpend.divide(BigDecimal.valueOf(payrollMonths), 2, RoundingMode.HALF_UP);
    BigDecimal twoMonthNeed = monthlyAvg.multiply(BigDecimal.valueOf(2));
    if (twoMonthNeed.compareTo(BigDecimal.ZERO) <= 0) return 0;
    BigDecimal balance = jdbcTemplate.queryForObject(
        "select coalesce(sum(current_balance), 0) from bank_account where tenant_id = ? and deleted_at is null",
        BigDecimal.class, tenantId);
    if (balance != null && balance.compareTo(twoMonthNeed) >= 0) return 0;
    boolean created = createException(tenantId, "payroll_gap", "funding_gap", 1L,
        "工资类支出资金缺口预警",
        "可用资金 " + balance + " 低于未来 2 个月工资/社保/报销类预测支出 " + twoMonthNeed
            + "（近 " + payrollMonths + " 个月月均 " + monthlyAvg + "），请提前安排资金",
        "high", null);
    return created ? 1 : 0;
  }

  /** §8.3-5 项目累计收款率低于合同时间进度（项目需有开始/交付日期）。 */
  private int alertProjectCashflow(Long tenantId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select p.id, p.project_no, p.project_name, p.start_date, p.delivery_date, "
            + "coalesce((select sum(r.plan_amount) from contract_receivable_plan r where r.project_id = p.id and r.deleted_at is null), 0) as receivable, "
            + "coalesce((select sum(r.paid_amount) from contract_receivable_plan r where r.project_id = p.id and r.deleted_at is null), 0) as paid "
            + "from project p where p.tenant_id = ? and p.deleted_at is null and p.project_status = 'active' "
            + "and p.start_date is not null and p.delivery_date is not null and p.delivery_date > p.start_date",
        tenantId);
    int created = 0;
    LocalDate today = LocalDate.now();
    for (Map<String, Object> row : rows) {
      LocalDate start = sqlDate(row.get("start_date"));
      LocalDate delivery = sqlDate(row.get("delivery_date"));
      if (today.isBefore(start) || today.isAfter(delivery)) continue;
      BigDecimal receivable = decimal(row.get("receivable"));
      if (receivable.compareTo(BigDecimal.ZERO) <= 0) continue;
      long total = delivery.toEpochDay() - start.toEpochDay();
      long elapsed = today.toEpochDay() - start.toEpochDay();
      BigDecimal timeProgress = BigDecimal.valueOf(elapsed)
          .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
      BigDecimal paidRate = decimal(row.get("paid")).divide(receivable, 4, RoundingMode.HALF_UP);
      if (paidRate.compareTo(timeProgress) >= 0) continue;
      if (createException(tenantId, "project_cashflow_risk", "project", number(row.get("id")),
          "项目现金流风险：" + row.get("project_name"),
          "项目 " + row.get("project_no") + " 时间进度 " + timeProgress.multiply(BigDecimal.valueOf(100))
              + "%，累计收款率仅 " + paidRate.multiply(BigDecimal.valueOf(100))
              + "%，收款低于合同进度应收比例",
          "medium", null)) created++;
    }
    return created;
  }

  private boolean createException(Long tenantId, String type, String sourceType, Long sourceId,
      String title, String description, String severity, LocalDate dueDate) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from exception_case where tenant_id = ? and exception_type = ? and source_type = ? and source_id = ? and deleted_at is null",
        Integer.class, tenantId, type, sourceType, sourceId);
    if (count != null && count > 0) return false;
    jdbcTemplate.update(
        "insert into exception_case (tenant_id, exception_no, exception_type, source_type, source_id, title, description, status, severity, due_date) values (?, ?, ?, ?, ?, ?, ?, 'new', ?, ?)",
        tenantId, "EXC-" + UUID.randomUUID().toString().replace("-", ""), type, sourceType, sourceId, title,
        description, severity, dueDate == null ? null : Date.valueOf(dueDate));
    autoAssignService.autoAssignBySource(tenantId, type, sourceType, sourceId);
    return true;
  }

  private int configInt(Long tenantId, String key, int defaultValue) {
    List<String> values = jdbcTemplate.queryForList(
        "select config_value from tenant_rule_config where tenant_id = ? and config_key = ?",
        String.class, tenantId, key);
    if (values.isEmpty()) return defaultValue;
    try {
      return Integer.parseInt(values.get(0));
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private Long number(Object value) { return ((Number) value).longValue(); }
  private BigDecimal decimal(Object value) { return new BigDecimal(String.valueOf(value)); }
  private LocalDate sqlDate(Object value) {
    return value instanceof Date ? ((Date) value).toLocalDate() : LocalDate.parse(String.valueOf(value));
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
