package com.bankforecast.metric;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** P2-4 试点数据采集：指标快照（手动采集、同日幂等覆盖）、历史趋势和试点验收指标。 */
@Service
public class MetricService {

  /** 验收指标定义：code、中文名、阈值（百分数，达到即达标）。 */
  private static final String[][] ACCEPTANCE_METRICS = {
      {"import_success_rate", "银行流水导入成功率", "95"},
      {"template_recognition_rate", "模板识别成功率", "90"},
      {"auto_match_rate", "自动匹配率", "70"},
      {"manual_confirm_rate", "人工确认率", "80"},
      {"exception_closure_rate", "异常闭环率", "90"},
      {"report_success_rate", "报表生成成功率", "100"}};
  private static final String[][] AMOUNT_METRICS = {
      {"total_balance", "账户总余额"},
      {"yesterday_net_inflow", "昨日净流入"},
      {"receivable_total", "应收总额"},
      {"received_total", "已收总额"},
      {"overdue_receivable", "逾期未收"},
      {"open_exception_count", "未闭环异常数"},
      {"match_rate", "流水匹配率"}};

  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;
  private final com.bankforecast.service.DashboardService dashboardService;
  private final com.bankforecast.matching.ExceptionAutoAssignService autoAssignService;

  public MetricService(JdbcTemplate jdbcTemplate, AuditService auditService,
      com.bankforecast.service.DashboardService dashboardService,
      com.bankforecast.matching.ExceptionAutoAssignService autoAssignService) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
    this.dashboardService = dashboardService;
    this.autoAssignService = autoAssignService;
  }

  /** 采集指定日期（默认今天）的全部指标快照；同日重复采集覆盖，幂等。 */
  @Transactional
  public Map<String, Object> collect(Long tenantId, Long userId, String dateText) {
    LocalDate date = dateText == null || dateText.trim().isEmpty()
        ? LocalDate.now() : parseDate(dateText);
    Map<String, BigDecimal> values = computeAll(tenantId, date);
    jdbcTemplate.update("delete from metric_snapshot where tenant_id = ? and snapshot_date = ?",
        tenantId, Date.valueOf(date));
    for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
      jdbcTemplate.update(
          "insert into metric_snapshot (tenant_id, snapshot_date, metric_code, metric_value) values (?, ?, ?, ?)",
          tenantId, Date.valueOf(date), entry.getKey(),
          entry.getValue() == null ? null : entry.getValue());
    }
    auditService.record("COLLECT_METRIC_SNAPSHOT", "metric_snapshot", String.valueOf(date),
        "metrics=" + values.size());
    checkFundingGap(tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("snapshot_date", date.toString());
    result.put("collected", values.size());
    return result;
  }

  /** 单指标历史趋势（默认近 30 天）。 */
  public List<Map<String, Object>> series(Long tenantId, String metricCode, String from, String to) {
    String code = requireMetricCode(metricCode);
    LocalDate toDate = to == null || to.trim().isEmpty() ? LocalDate.now() : parseDate(to);
    LocalDate fromDate = from == null || from.trim().isEmpty()
        ? toDate.minusDays(29) : parseDate(from);
    if (fromDate.isAfter(toDate)) throw new BusinessException(ErrorCode.PARAM_ERROR, "from 不能晚于 to");
    return jdbcTemplate.queryForList(
        "select snapshot_date, metric_value from metric_snapshot where tenant_id = ? and metric_code = ? and snapshot_date between ? and ? order by snapshot_date",
        tenantId, code, Date.valueOf(fromDate), Date.valueOf(toDate));
  }

  /** 试点验收指标：实时计算当前值、阈值和达标状态（不依赖快照），附金额指标当前值。 */
  public Map<String, Object> pilotAcceptance(Long tenantId) {
    LocalDate today = LocalDate.now();
    Map<String, BigDecimal> values = computeAll(tenantId, today);
    List<Map<String, Object>> indicators = new ArrayList<>();
    for (String[] def : ACCEPTANCE_METRICS) {
      Map<String, Object> item = new LinkedHashMap<>();
      BigDecimal value = values.get(def[0]);
      BigDecimal threshold = new BigDecimal(def[2]);
      item.put("metric_code", def[0]);
      item.put("label", def[1]);
      item.put("value", value);
      item.put("threshold", threshold);
      item.put("pass", value != null && value.compareTo(threshold) >= 0);
      indicators.add(item);
    }
    List<Map<String, Object>> amounts = new ArrayList<>();
    for (String[] def : AMOUNT_METRICS) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("metric_code", def[0]);
      item.put("label", def[1]);
      item.put("value", values.get(def[0]));
      amounts.add(item);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("as_of", today.toString());
    result.put("indicators", indicators);
    result.put("amounts", amounts);
    result.put("last_snapshot_date", jdbcTemplate.queryForObject(
        "select max(snapshot_date) from metric_snapshot where tenant_id = ?", String.class, tenantId));
    return result;
  }

  private Map<String, BigDecimal> computeAll(Long tenantId, LocalDate date) {
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    Date cutoff = Date.valueOf(date.plusDays(1));

    Map<String, Object> imports = jdbcTemplate.queryForMap(
        "select coalesce(sum(success_rows), 0) as success_rows, coalesce(sum(total_rows), 0) as total_rows "
            + "from import_job where tenant_id = ? and job_type = 'bank_statement' and deleted_at is null and created_at < ?",
        tenantId, cutoff);
    values.put("import_success_rate",
        rate((Number) imports.get("success_rows"), (Number) imports.get("total_rows")));

    Map<String, Object> templates = jdbcTemplate.queryForMap(
        "select count(*) as xlsx_jobs, coalesce(sum(case when templates_recognized > 0 then 1 else 0 end), 0) as recognized "
            + "from import_job where tenant_id = ? and job_type = 'bank_statement' and lower(file_name) like '%.xlsx' and deleted_at is null and created_at < ?",
        tenantId, cutoff);
    values.put("template_recognition_rate",
        rate((Number) templates.get("recognized"), (Number) templates.get("xlsx_jobs")));

    Map<String, Object> transactions = jdbcTemplate.queryForMap(
        "select count(*) as total, coalesce(sum(case when match_status = 'matched' then 1 else 0 end), 0) as auto_matched, "
            + "coalesce(sum(case when match_status in ('matched', 'manual_confirmed') then 1 else 0 end), 0) as matched "
            + "from bank_transaction where tenant_id = ? and deleted_at is null and created_at < ?",
        tenantId, cutoff);
    values.put("auto_match_rate",
        rate((Number) transactions.get("auto_matched"), (Number) transactions.get("total")));
    values.put("match_rate", rate((Number) transactions.get("matched"), (Number) transactions.get("total")));

    Map<String, Object> confirms = jdbcTemplate.queryForMap(
        "select coalesce(sum(case when match_status in ('confirmed', 'rejected') then 1 else 0 end), 0) as handled, "
            + "coalesce(sum(case when match_status in ('confirmed', 'rejected', 'suggested') then 1 else 0 end), 0) as pending_total "
            + "from match_result where tenant_id = ? and deleted_at is null and created_at < ?",
        tenantId, cutoff);
    values.put("manual_confirm_rate",
        rate((Number) confirms.get("handled"), (Number) confirms.get("pending_total")));

    Map<String, Object> exceptions = jdbcTemplate.queryForMap(
        "select count(*) as total, coalesce(sum(case when status in ('resolved', 'closed', 'false_positive') then 1 else 0 end), 0) as closed "
            + "from exception_case where tenant_id = ? and deleted_at is null and created_at < ?",
        tenantId, cutoff);
    values.put("exception_closure_rate",
        rate((Number) exceptions.get("closed"), (Number) exceptions.get("total")));

    Map<String, Object> reports = jdbcTemplate.queryForMap(
        "select count(*) as total, coalesce(sum(case when status = 'success' then 1 else 0 end), 0) as success "
            + "from report_task where tenant_id = ? and deleted_at is null and created_at < ?",
        tenantId, cutoff);
    values.put("report_success_rate",
        rate((Number) reports.get("success"), (Number) reports.get("total")));

    values.put("total_balance", amount(jdbcTemplate.queryForObject(
        "select coalesce(sum(current_balance), 0) from bank_account where tenant_id = ? and deleted_at is null",
        BigDecimal.class, tenantId)));
    LocalDate dayBefore = date.minusDays(1);
    BigDecimal income = amount(jdbcTemplate.queryForObject(
        "select coalesce(sum(amount), 0) from bank_transaction where tenant_id = ? and transaction_date = ? and direction in ('income', 'refund') and deleted_at is null",
        BigDecimal.class, tenantId, Date.valueOf(dayBefore)));
    BigDecimal expense = amount(jdbcTemplate.queryForObject(
        "select coalesce(sum(amount), 0) from bank_transaction where tenant_id = ? and transaction_date = ? and direction in ('expense', 'reversal') and deleted_at is null",
        BigDecimal.class, tenantId, Date.valueOf(dayBefore)));
    values.put("yesterday_net_inflow", income.subtract(expense));
    values.put("receivable_total", amount(jdbcTemplate.queryForObject(
        "select coalesce(sum(plan_amount), 0) from contract_receivable_plan where tenant_id = ? and deleted_at is null",
        BigDecimal.class, tenantId)));
    values.put("received_total", amount(jdbcTemplate.queryForObject(
        "select coalesce(sum(paid_amount), 0) from contract_receivable_plan where tenant_id = ? and deleted_at is null",
        BigDecimal.class, tenantId)));
    values.put("overdue_receivable", amount(jdbcTemplate.queryForObject(
        "select coalesce(sum(plan_amount - paid_amount), 0) from contract_receivable_plan where tenant_id = ? and deleted_at is null and status <> 'paid' and due_date < ?",
        BigDecimal.class, tenantId, Date.valueOf(date))));
    Integer open = jdbcTemplate.queryForObject(
        "select count(*) from exception_case where tenant_id = ? and status not in ('resolved', 'closed', 'false_positive') and deleted_at is null",
        Integer.class, tenantId);
    values.put("open_exception_count", BigDecimal.valueOf(open == null ? 0 : open));
    return values;
  }

  /** 资金缺口为负时生成 fund_gap 异常；已存在未闭环的缺口异常则更新描述，不重复生成。 */
  private void checkFundingGap(Long tenantId) {
    Map<String, Object> gap = dashboardService.fundingGap(tenantId);
    if (!Boolean.TRUE.equals(gap.get("has_gap"))) return;
    String title = "资金缺口预警";
    String description = "未来 30 天预计资金缺口 " + gap.get("gap_amount") + "（余额 " + gap.get("balance")
        + " + 应收 " + gap.get("receivable_30d") + " − 应付 " + gap.get("payable_30d") + "）";
    Integer active = jdbcTemplate.queryForObject(
        "select count(*) from exception_case where tenant_id = ? and exception_type = 'fund_gap' and source_type = 'funding_gap' and status not in ('closed', 'false_positive') and deleted_at is null",
        Integer.class, tenantId);
    if (active != null && active > 0) {
      jdbcTemplate.update(
          "update exception_case set title = ?, description = ?, updated_at = current_timestamp where tenant_id = ? and exception_type = 'fund_gap' and source_type = 'funding_gap' and status not in ('closed', 'false_positive') and deleted_at is null",
          title, description, tenantId);
      return;
    }
    jdbcTemplate.update(
        "insert into exception_case (tenant_id, exception_no, exception_type, source_type, source_id, title, description, status, severity) values (?, ?, 'fund_gap', 'funding_gap', 0, ?, ?, 'new', 'high')",
        tenantId, "EXC-" + java.util.UUID.randomUUID().toString().replace("-", ""), title, description);
    autoAssignService.autoAssignBySource(tenantId, "fund_gap", "funding_gap", 0L);
  }

  private BigDecimal rate(Number numerator, Number denominator) {
    long total = denominator == null ? 0 : denominator.longValue();
    if (total == 0) return null;
    long part = numerator == null ? 0 : numerator.longValue();
    return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private String requireMetricCode(String metricCode) {
    String code = metricCode == null ? "" : metricCode.trim();
    for (String[] def : ACCEPTANCE_METRICS) if (def[0].equals(code)) return code;
    for (String[] def : AMOUNT_METRICS) if (def[0].equals(code)) return code;
    throw new BusinessException(ErrorCode.PARAM_ERROR, "metric_code 不支持：" + code);
  }

  private LocalDate parseDate(String value) {
    try {
      return LocalDate.parse(value.trim());
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "日期格式应为 yyyy-MM-dd");
    }
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
