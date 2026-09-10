package com.bankforecast.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final AuditService auditService;

  public ReportService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AuditService auditService) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.auditService = auditService;
  }

  @Transactional
  public Map<String, Object> create(String reportType, Map<String, Object> params) {
    AuthPrincipal principal = requireAuth();
    String normalizedType = normalizeType(reportType);
    DateRange range = resolveRange(normalizedType, params == null ? Collections.emptyMap() : params);
    Long reportId = insertTask(principal, normalizedType, range);
    try {
      Map<String, Object> result = buildResult(principal.getTenantId(), normalizedType, range);
      String json = writeJson(result);
      String fileName = fileName(normalizedType, range);
      String csv = toCsv(normalizedType, result, range);
      jdbcTemplate.update("update report_task set status = 'success', file_name = ?, result_json = ?, file_content = ?, updated_at = current_timestamp where id = ? and tenant_id = ?",
          fileName, json, csv, reportId, principal.getTenantId());
      auditService.record("CREATE_REPORT", "report_task", String.valueOf(reportId), "report_type=" + normalizedType);
      return detail(principal.getTenantId(), reportId);
    } catch (Exception ex) {
      jdbcTemplate.update("update report_task set status = 'failed', error_message = ?, updated_at = current_timestamp where id = ? and tenant_id = ?",
          safeMessage(ex), reportId, principal.getTenantId());
      if (ex instanceof BusinessException) throw (BusinessException) ex;
      throw new BusinessException(ErrorCode.REPORT_GENERATE_FAILED, "报表生成失败，请稍后重试");
    }
  }

  public List<Map<String, Object>> list(String reportType, String status) {
    AuthPrincipal principal = requireAuth();
    return jdbcTemplate.queryForList(
        "select id, report_type, date_from, date_to, status, file_name, error_message, created_at, updated_at from report_task where tenant_id = ? and deleted_at is null and (? is null or report_type = ?) and (? is null or status = ?) order by id desc limit 100",
        principal.getTenantId(), blankToNull(reportType), blankToNull(reportType), blankToNull(status), blankToNull(status));
  }

  public Map<String, Object> detail(Long reportId) {
    AuthPrincipal principal = requireAuth();
    return detail(principal.getTenantId(), reportId);
  }

  public String download(Long reportId) {
    AuthPrincipal principal = requireAuth();
    Map<String, Object> row = find(principal.getTenantId(), reportId);
    if (!"success".equals(text(row.get("status"))) || row.get("file_content") == null) {
      throw new BusinessException(ErrorCode.FILE_NOT_AVAILABLE, "报表文件不可用");
    }
    auditService.record("DOWNLOAD_REPORT", "report_task", String.valueOf(reportId), "file_name=" + text(row.get("file_name")));
    return text(row.get("file_content"));
  }

  private Map<String, Object> detail(Long tenantId, Long reportId) {
    Map<String, Object> row = find(tenantId, reportId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", row.get("id"));
    result.put("report_type", row.get("report_type"));
    result.put("date_from", row.get("date_from"));
    result.put("date_to", row.get("date_to"));
    result.put("status", row.get("status"));
    result.put("file_name", row.get("file_name"));
    result.put("error_message", row.get("error_message"));
    result.put("created_at", row.get("created_at"));
    result.put("updated_at", row.get("updated_at"));
    if (row.get("result_json") != null) {
      try {
        result.put("result", objectMapper.readValue(text(row.get("result_json")), new TypeReference<Map<String, Object>>() { }));
      } catch (Exception ex) {
        throw new BusinessException(ErrorCode.REPORT_FILE_INVALID, "报表结果不可读取");
      }
    }
    return result;
  }

  private Map<String, Object> find(Long tenantId, Long reportId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select id, report_type, date_from, date_to, status, file_name, result_json, file_content, error_message, created_at, updated_at from report_task where id = ? and tenant_id = ? and deleted_at is null",
        reportId, tenantId);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "报表不存在");
    return rows.get(0);
  }

  private Long insertTask(AuthPrincipal principal, String reportType, DateRange range) {
    KeyHolder holder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement statement = connection.prepareStatement(
          "insert into report_task (tenant_id, report_type, date_from, date_to, status, created_by) values (?, ?, ?, ?, 'running', ?)",
          Statement.RETURN_GENERATED_KEYS);
      statement.setLong(1, principal.getTenantId());
      statement.setString(2, reportType);
      if (range.from == null) statement.setDate(3, null); else statement.setDate(3, Date.valueOf(range.from));
      if (range.to == null) statement.setDate(4, null); else statement.setDate(4, Date.valueOf(range.to));
      statement.setLong(5, principal.getUserId());
      return statement;
    }, holder);
    if (holder.getKeys() != null && holder.getKeys().get("id") != null) return ((Number) holder.getKeys().get("id")).longValue();
    return holder.getKey().longValue();
  }

  private Map<String, Object> buildResult(Long tenantId, String reportType, DateRange range) {
    if ("health".equals(reportType)) return health(tenantId, range);
    return cashflow(tenantId, reportType, range);
  }

  private Map<String, Object> cashflow(Long tenantId, String reportType, DateRange range) {
    BigDecimal income = sumTransactions(tenantId, range, "income", "refund");
    BigDecimal expense = sumTransactions(tenantId, range, "expense", "reversal");
    Integer transactionCount = jdbcTemplate.queryForObject(
        "select count(*) from bank_transaction where tenant_id = ? and deleted_at is null and (? is null or transaction_date >= ?) and (? is null or transaction_date <= ?)",
        Integer.class, tenantId, sqlDate(range.from), sqlDate(range.from), sqlDate(range.to), sqlDate(range.to));
    BigDecimal receivablePlan = queryAmount("select coalesce(sum(plan_amount), 0) from contract_receivable_plan where tenant_id = ? and deleted_at is null", tenantId);
    BigDecimal receivablePaid = queryAmount("select coalesce(sum(paid_amount), 0) from contract_receivable_plan where tenant_id = ? and deleted_at is null", tenantId);
    BigDecimal overdue = queryAmount("select coalesce(sum(plan_amount - paid_amount), 0) from contract_receivable_plan where tenant_id = ? and due_date < ? and status <> 'paid' and deleted_at is null", tenantId, Date.valueOf(LocalDate.now()));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("report_name", "monthly".equals(reportType) ? "月度资金报表" : "日报");
    result.put("report_type", reportType);
    result.put("date_from", range.from);
    result.put("date_to", range.to);
    result.put("total_balance", queryAmount("select coalesce(sum(current_balance), 0) from bank_account where tenant_id = ? and status <> 'closed' and deleted_at is null", tenantId));
    result.put("income_total", income);
    result.put("expense_total", expense);
    result.put("net_cashflow", income.subtract(expense).setScale(2, RoundingMode.HALF_UP));
    result.put("transaction_count", transactionCount == null ? 0 : transactionCount);
    result.put("receivable_plan_total", receivablePlan);
    result.put("receivable_paid_total", receivablePaid);
    result.put("overdue_receivable_total", overdue);
    result.put("pending_exception_count", pendingExceptions(tenantId));
    result.put("match_rate", matchRate(tenantId));
    result.put("daily_breakdown", dailyBreakdown(tenantId, range));
    return result;
  }

  private Map<String, Object> health(Long tenantId, DateRange range) {
    Map<String, Object> base = cashflow(tenantId, "health", range);
    int idle = count("select count(*) from bank_account where tenant_id = ? and status = 'idle' and deleted_at is null", tenantId);
    int pending = number(base.get("pending_exception_count"));
    int unmatchedIncome = count("select count(*) from bank_transaction where tenant_id = ? and direction in ('income', 'refund') and match_status = 'unmatched' and deleted_at is null", tenantId);
    int score = Math.max(0, 100 - Math.min(40, idle * 10) - Math.min(35, pending * 5) - Math.min(25, unmatchedIncome * 5));
    String level = score >= 80 ? "healthy" : score >= 60 ? "warning" : "danger";
    List<Map<String, Object>> risks = new ArrayList<>();
    if (idle > 0) risks.add(risk("闲置账户", idle, "存在已识别的闲置银行账户"));
    if (pending > 0) risks.add(risk("待处理异常", pending, "异常事项尚未全部闭环"));
    if (unmatchedIncome > 0) risks.add(risk("未知收款", unmatchedIncome, "收入流水尚未匹配到合同或项目"));
    base.put("report_name", "资金体检报告");
    base.put("health_score", score);
    base.put("health_level", level);
    base.put("risk_items", risks);
    return base;
  }

  private List<Map<String, Object>> dailyBreakdown(Long tenantId, DateRange range) {
    return jdbcTemplate.queryForList(
        "select transaction_date, coalesce(sum(case when direction in ('income', 'refund') then amount else 0 end), 0) as income_total, coalesce(sum(case when direction in ('expense', 'reversal') then amount else 0 end), 0) as expense_total, count(*) as transaction_count from bank_transaction where tenant_id = ? and deleted_at is null and (? is null or transaction_date >= ?) and (? is null or transaction_date <= ?) group by transaction_date order by transaction_date",
        tenantId, sqlDate(range.from), sqlDate(range.from), sqlDate(range.to), sqlDate(range.to));
  }

  private BigDecimal sumTransactions(Long tenantId, DateRange range, String first, String second) {
    return queryAmount("select coalesce(sum(amount), 0) from bank_transaction where tenant_id = ? and direction in (?, ?) and deleted_at is null and (? is null or transaction_date >= ?) and (? is null or transaction_date <= ?)",
        tenantId, first, second, sqlDate(range.from), sqlDate(range.from), sqlDate(range.to), sqlDate(range.to));
  }

  private BigDecimal queryAmount(String sql, Object... args) {
    BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
    return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
  }

  private int pendingExceptions(Long tenantId) { return count("select count(*) from exception_case where tenant_id = ? and status not in ('closed', 'resolved') and deleted_at is null", tenantId); }
  private double matchRate(Long tenantId) {
    int total = count("select count(*) from bank_transaction where tenant_id = ? and deleted_at is null", tenantId);
    if (total == 0) return 0D;
    return count("select count(*) from bank_transaction where tenant_id = ? and match_status in ('matched', 'manual_confirmed') and deleted_at is null", tenantId) / (double) total;
  }
  private int count(String sql, Object... args) { Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args); return value == null ? 0 : value; }
  private Map<String, Object> risk(String title, int count, String description) { Map<String, Object> item = new LinkedHashMap<>(); item.put("title", title); item.put("count", count); item.put("description", description); return item; }

  private String toCsv(String reportType, Map<String, Object> result, DateRange range) {
    StringBuilder csv = new StringBuilder("\uFEFF指标,数值\n");
    for (Map.Entry<String, Object> entry : result.entrySet()) {
      if (entry.getValue() instanceof List || entry.getValue() instanceof Map) continue;
      csv.append(escape(entry.getKey())).append(',').append(escape(String.valueOf(entry.getValue()))).append('\n');
    }
    if ("health".equals(reportType)) {
      csv.append("风险项,\n");
      List<?> risks = (List<?>) result.get("risk_items");
      for (Object risk : risks) csv.append(escape(writeJson(risk))).append(",\n");
    }
    return csv.toString();
  }

  private String escape(String value) { String text = value == null ? "" : value; return "\"" + text.replace("\"", "\"\"") + "\""; }
  private String writeJson(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { throw new BusinessException(ErrorCode.REPORT_GENERATE_FAILED, "报表结果序列化失败"); } }
  private String fileName(String type, DateRange range) { return "health".equals(type) ? "cash-health-report-" + range.label + ".csv" : type + "-cash-report-" + range.label + ".csv"; }
  private Date sqlDate(LocalDate value) { return value == null ? null : Date.valueOf(value); }
  private String safeMessage(Exception ex) { return ex.getMessage() == null ? "报表生成失败" : ex.getMessage().substring(0, Math.min(1000, ex.getMessage().length())); }
  private String text(Object value) { return value == null ? "" : String.valueOf(value); }
  private int number(Object value) { return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(text(value)); }
  private String blankToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
  private AuthPrincipal requireAuth() { if (AuthContext.get() == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录"); return AuthContext.get(); }

  private String normalizeType(String reportType) {
    String type = reportType == null ? "" : reportType.trim().toLowerCase();
    if (!type.equals("daily") && !type.equals("monthly") && !type.equals("health")) throw new BusinessException(ErrorCode.REPORT_TYPE_INVALID, "报表类型不支持，仅支持 daily、monthly、health");
    return type;
  }

  private DateRange resolveRange(String reportType, Map<String, Object> params) {
    LocalDate from = date(params.get("date_from"));
    LocalDate to = date(params.get("date_to"));
    String month = text(params.get("month"));
    if ("monthly".equals(reportType) && month.length() > 0) {
      YearMonth yearMonth;
      try { yearMonth = YearMonth.parse(month); } catch (Exception ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, "month 格式必须为 YYYY-MM"); }
      from = yearMonth.atDay(1); to = yearMonth.atEndOfMonth();
    } else if ("daily".equals(reportType) && from == null && to == null) {
      from = LocalDate.now(); to = from;
    } else if ("health".equals(reportType) && from == null && to == null) {
      from = LocalDate.now().minusDays(30); to = LocalDate.now();
    }
    if (from != null && to != null && from.isAfter(to)) throw new BusinessException(ErrorCode.PARAM_ERROR, "date_from 不能晚于 date_to");
    String label = "monthly".equals(reportType) && month.length() > 0 ? month.replace("-", "") : (from == null ? "all" : from.toString().replace("-", "") + (to != null && !from.equals(to) ? "-" + to.toString().replace("-", "") : ""));
    return new DateRange(from, to, label);
  }

  private LocalDate date(Object value) {
    if (value == null || text(value).trim().isEmpty()) return null;
    try { return LocalDate.parse(text(value)); } catch (Exception ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, "日期格式必须为 YYYY-MM-DD"); }
  }

  private static final class DateRange {
    private final LocalDate from;
    private final LocalDate to;
    private final String label;
    private DateRange(LocalDate from, LocalDate to, String label) { this.from = from; this.to = to; this.label = label; }
  }
}
