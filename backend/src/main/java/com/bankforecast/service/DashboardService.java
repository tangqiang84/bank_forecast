package com.bankforecast.service;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
  private final JdbcTemplate jdbcTemplate;
  public DashboardService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

  /**
   * 驾驶舱主指标近 N 天趋势（2026-10-10）。
   * 收支/净流入/余额/未闭环异常/匹配率为实时重算；余额按「当前余额 − 之后日净额」回推；
   * 应收/已收/逾期取 metric_snapshot 快照值（无快照日期为 null）。
   */
  public Map<String, Object> trends(Long tenantId, int days) {
    if (days < 1 || days > 90) throw new BusinessException(ErrorCode.PARAM_ERROR, "days 需为 1-90");
    LocalDate end = LocalDate.now();
    LocalDate start = end.minusDays(days - 1L);

    Map<LocalDate, BigDecimal> incomeByDate = new HashMap<>();
    Map<LocalDate, BigDecimal> expenseByDate = new HashMap<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(
        "select transaction_date, coalesce(sum(case when direction in ('income', 'refund') then amount else 0 end), 0) as income, "
            + "coalesce(sum(case when direction in ('expense', 'reversal') then amount else 0 end), 0) as expense "
            + "from bank_transaction where tenant_id = ? and deleted_at is null and transaction_date between ? and ? group by transaction_date",
        tenantId, Date.valueOf(start), Date.valueOf(end))) {
      LocalDate date = ((Date) row.get("transaction_date")).toLocalDate();
      incomeByDate.put(date, valueOrZero((BigDecimal) row.get("income")));
      expenseByDate.put(date, valueOrZero((BigDecimal) row.get("expense")));
    }
    BigDecimal currentBalance = valueOrZero(jdbcTemplate.queryForObject(
        "select coalesce(sum(current_balance), 0) from bank_account where tenant_id = ? and deleted_at is null",
        BigDecimal.class, tenantId));

    Map<LocalDate, Integer> exceptionCreatedByDate = countByDate(tenantId,
        "select cast(created_at as date) as d, count(*) as c from exception_case where tenant_id = ? and deleted_at is null group by cast(created_at as date)");
    Map<LocalDate, Integer> exceptionClosedByDate = countByDate(tenantId,
        "select cast(closed_at as date) as d, count(*) as c from exception_case where tenant_id = ? and deleted_at is null and closed_at is not null group by cast(closed_at as date)");

    Map<LocalDate, Integer> txnCreatedByDate = countByDate(tenantId,
        "select cast(created_at as date) as d, count(*) as c from bank_transaction where tenant_id = ? and deleted_at is null group by cast(created_at as date)");
    Map<LocalDate, Integer> txnMatchedByDate = countByDate(tenantId,
        "select cast(created_at as date) as d, count(*) as c from bank_transaction where tenant_id = ? and deleted_at is null and match_status in ('matched', 'manual_confirmed') group by cast(created_at as date)");

    Map<String, Map<LocalDate, BigDecimal>> snapshots = new HashMap<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(
        "select snapshot_date, metric_code, metric_value from metric_snapshot where tenant_id = ? and metric_code in ('receivable_total', 'received_total', 'overdue_receivable') and snapshot_date between ? and ?",
        tenantId, Date.valueOf(start), Date.valueOf(end))) {
      snapshots.computeIfAbsent(String.valueOf(row.get("metric_code")), key -> new HashMap<>())
          .put(((Date) row.get("snapshot_date")).toLocalDate(), (BigDecimal) row.get("metric_value"));
    }

    int openExceptionsBefore = jdbcTemplate.queryForObject(
        "select count(*) from exception_case where tenant_id = ? and deleted_at is null and created_at < ? and (closed_at is null or closed_at >= ?)",
        Integer.class, tenantId, Date.valueOf(start), Date.valueOf(start));
    int txnTotalBefore = countBefore(tenantId, "bank_transaction", start, false);
    int txnMatchedBefore = countBefore(tenantId, "bank_transaction", start, true);

    List<Map<String, Object>> series = new ArrayList<>();
    int openExceptions = openExceptionsBefore;
    int txnTotal = txnTotalBefore;
    int txnMatched = txnMatchedBefore;
    // 余额回推：balance(d) = currentBalance − sum(net(d+1..end])，从 end 往前累计
    Map<LocalDate, BigDecimal> balanceByDate = new HashMap<>();
    BigDecimal suffixNet = BigDecimal.ZERO;
    for (int offset = days - 1; offset >= 0; offset--) {
      LocalDate date = start.plusDays(offset);
      BigDecimal net = incomeByDate.getOrDefault(date, BigDecimal.ZERO)
          .subtract(expenseByDate.getOrDefault(date, BigDecimal.ZERO));
      balanceByDate.put(date, currentBalance.subtract(suffixNet));
      suffixNet = suffixNet.add(net);
    }
    for (int offset = 0; offset < days; offset++) {
      LocalDate date = start.plusDays(offset);
      BigDecimal income = incomeByDate.getOrDefault(date, BigDecimal.ZERO);
      BigDecimal expense = expenseByDate.getOrDefault(date, BigDecimal.ZERO);
      openExceptions += exceptionCreatedByDate.getOrDefault(date, 0) - exceptionClosedByDate.getOrDefault(date, 0);
      txnTotal += txnCreatedByDate.getOrDefault(date, 0);
      txnMatched += txnMatchedByDate.getOrDefault(date, 0);
      Map<String, Object> point = new LinkedHashMap<>();
      point.put("date", date.toString());
      point.put("income", income);
      point.put("expense", expense);
      point.put("net_inflow", income.subtract(expense));
      point.put("balance", balanceByDate.get(date));
      point.put("open_exceptions", openExceptions);
      point.put("match_rate", txnTotal == 0 ? null
          : BigDecimal.valueOf(txnMatched).multiply(BigDecimal.valueOf(100))
              .divide(BigDecimal.valueOf(txnTotal), 2, RoundingMode.HALF_UP));
      point.put("receivable_total", snapshotValue(snapshots, "receivable_total", date));
      point.put("received_total", snapshotValue(snapshots, "received_total", date));
      point.put("overdue_receivable", snapshotValue(snapshots, "overdue_receivable", date));
      series.add(point);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("days", days);
    result.put("start", start.toString());
    result.put("end", end.toString());
    result.put("series", series);
    return result;
  }

  private Map<LocalDate, Integer> countByDate(Long tenantId, String sql) {
    Map<LocalDate, Integer> result = new HashMap<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(sql, tenantId)) {
      result.put(((Date) row.get("d")).toLocalDate(), ((Number) row.get("c")).intValue());
    }
    return result;
  }

  private int countBefore(Long tenantId, String table, LocalDate start, boolean matchedOnly) {
    String condition = matchedOnly ? " and match_status in ('matched', 'manual_confirmed')" : "";
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where tenant_id = ? and deleted_at is null and created_at < ?" + condition,
        Integer.class, tenantId, Date.valueOf(start));
    return count == null ? 0 : count;
  }

  private BigDecimal snapshotValue(Map<String, Map<LocalDate, BigDecimal>> snapshots, String code, LocalDate date) {
    Map<LocalDate, BigDecimal> byDate = snapshots.get(code);
    return byDate == null ? null : byDate.get(date);
  }

  public Map<String, Object> overview(Long tenantId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("total_balance", valueOrZero(jdbcTemplate.queryForObject("select coalesce(sum(current_balance), 0) from bank_account where tenant_id = ? and deleted_at is null", BigDecimal.class, tenantId)));
    data.put("yesterday_net_inflow", yesterdayNetInflow(tenantId)); data.put("pending_exceptions", pendingExceptions(tenantId)); data.put("idle_accounts", countAccounts(tenantId, "idle")); data.put("match_rate", matchRate(tenantId)); data.put("last_sync_at", latestImportTime(tenantId)); data.put("receivable_amount", amount(tenantId, "plan_amount")); data.put("paid_receivable_amount", amount(tenantId, "paid_amount")); data.put("overdue_receivable_amount", overdueAmount(tenantId)); data.put("exception_count", totalExceptions(tenantId)); data.put("top_receivables", topReceivables(tenantId)); data.put("recent_import_jobs", recentImportJobs(tenantId)); data.put("key_risks", keyRisks(tenantId));
    return data;
  }
  private BigDecimal yesterdayNetInflow(Long tenantId) { LocalDate date = LocalDate.now().minusDays(1); BigDecimal income = jdbcTemplate.queryForObject("select coalesce(sum(amount), 0) from bank_transaction where tenant_id = ? and transaction_date = ? and direction in ('income', 'refund') and deleted_at is null", BigDecimal.class, tenantId, date); BigDecimal expense = jdbcTemplate.queryForObject("select coalesce(sum(amount), 0) from bank_transaction where tenant_id = ? and transaction_date = ? and direction in ('expense', 'reversal') and deleted_at is null", BigDecimal.class, tenantId, date); return valueOrZero(income).subtract(valueOrZero(expense)); }
  private double matchRate(Long tenantId) { Integer total = jdbcTemplate.queryForObject("select count(*) from bank_transaction where tenant_id = ? and deleted_at is null", Integer.class, tenantId); if (total == null || total == 0) return 0D; Integer matched = jdbcTemplate.queryForObject("select count(*) from bank_transaction where tenant_id = ? and deleted_at is null and match_status in ('matched', 'manual_confirmed')", Integer.class, tenantId); return (matched == null ? 0D : matched) / total.doubleValue(); }
  private String latestImportTime(Long tenantId) { return jdbcTemplate.queryForObject("select max(finished_at) from import_job where tenant_id = ? and deleted_at is null", String.class, tenantId); }
  private List<Map<String, Object>> recentImportJobs(Long tenantId) { return jdbcTemplate.queryForList("select id as job_id, job_type as name, status, total_rows, success_rows, failed_rows, skipped_rows, error_message as message from import_job where tenant_id = ? and deleted_at is null order by id desc limit 5", tenantId); }
  private BigDecimal amount(Long tenantId, String column) { return valueOrZero(jdbcTemplate.queryForObject("select coalesce(sum(" + column + "), 0) from contract_receivable_plan where tenant_id = ? and deleted_at is null", BigDecimal.class, tenantId)); }
  private BigDecimal overdueAmount(Long tenantId) { return valueOrZero(jdbcTemplate.queryForObject("select coalesce(sum(plan_amount - paid_amount), 0) from contract_receivable_plan where tenant_id = ? and deleted_at is null and status <> 'paid' and due_date < ?", BigDecimal.class, tenantId, java.sql.Date.valueOf(LocalDate.now()))); }
  private int totalExceptions(Long tenantId) { Integer count = jdbcTemplate.queryForObject("select count(*) from exception_case where tenant_id = ? and deleted_at is null", Integer.class, tenantId); return count == null ? 0 : count; }
  private List<Map<String, Object>> topReceivables(Long tenantId) { return jdbcTemplate.queryForList("select c.contract_name, c.customer_name, p.plan_amount - p.paid_amount as amount, p.due_date, p.status from contract_receivable_plan p join contract c on c.id = p.contract_id where p.tenant_id = ? and p.deleted_at is null and c.deleted_at is null and p.status <> 'paid' order by (p.plan_amount - p.paid_amount) desc, p.due_date asc limit 5", tenantId); }
  private List<Map<String, Object>> keyRisks(Long tenantId) { List<Map<String, Object>> risks = new ArrayList<>(); int idleAccounts = countAccounts(tenantId, "idle"); if (idleAccounts > 0) risks.add(risk("账户闲置", idleAccounts, "账户状态已标记为闲置")); Integer unmatchedIncome = jdbcTemplate.queryForObject("select count(*) from bank_transaction where tenant_id = ? and deleted_at is null and direction in ('income', 'refund') and match_status = 'unmatched'", Integer.class, tenantId); if (unmatchedIncome != null && unmatchedIncome > 0) risks.add(risk("未知收款", unmatchedIncome, "尚未匹配到合同或项目的到账流水")); return risks; }
  private int pendingExceptions(Long tenantId) { Integer count = jdbcTemplate.queryForObject("select count(*) from exception_case where tenant_id = ? and status not in ('closed', 'resolved') and deleted_at is null", Integer.class, tenantId); return count == null ? 0 : count; }
  private int countAccounts(Long tenantId, String status) { Integer count = jdbcTemplate.queryForObject("select count(*) from bank_account where tenant_id = ? and status = ? and deleted_at is null", Integer.class, tenantId, status); return count == null ? 0 : count; }
  private BigDecimal valueOrZero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
  private Map<String, Object> risk(String title, int count, String description) { Map<String, Object> item = new LinkedHashMap<>(); item.put("title", title); item.put("count", count); item.put("description", description); return item; }
}
