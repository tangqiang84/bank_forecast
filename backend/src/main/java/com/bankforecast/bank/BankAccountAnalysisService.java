package com.bankforecast.bank;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 账户分析视图（2026-10-10）：活跃度排名（近 90 天经营性动账笔数+金额）、
 * 近 N 天余额趋势（按账户从流水回推）、账户用途分布和销户建议
 * （近 180 天无动账 + 余额低于 1 万 + 非工资/税务用途）。
 */
@Service
public class BankAccountAnalysisService {

  private static final int ACTIVITY_WINDOW_DAYS = 90;
  private static final int CLOSE_IDLE_DAYS = 180;
  private static final BigDecimal CLOSE_BALANCE_THRESHOLD = new BigDecimal("10000.00");
  private static final int TREND_ACCOUNT_LIMIT = 10;

  private final JdbcTemplate jdbcTemplate;

  public BankAccountAnalysisService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Map<String, Object> analysis(int days) {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    if (days < 1 || days > 90) throw new BusinessException(ErrorCode.PARAM_ERROR, "days 需为 1-90");
    List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
        "select id, bank_name, account_name, account_no_last4, currency, status, current_balance, use_type "
            + "from bank_account where tenant_id = ? and deleted_at is null order by current_balance desc, id",
        tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("activity_window_days", ACTIVITY_WINDOW_DAYS);
    result.put("activity_ranking", activityRanking(tenantId, accounts));
    result.put("balance_trend", balanceTrend(tenantId, accounts, days));
    result.put("use_type_distribution", useTypeDistribution(tenantId));
    result.put("close_suggestions", closeSuggestions(tenantId));
    return result;
  }

  private List<Map<String, Object>> activityRanking(Long tenantId, List<Map<String, Object>> accounts) {
    Map<Long, Map<String, Object>> stats = new HashMap<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(
        "select bank_account_id, count(*) as txn_count, coalesce(sum(amount), 0) as txn_amount, max(transaction_date) as last_transaction_date "
            + "from bank_transaction where tenant_id = ? and deleted_at is null and direction in ('income', 'expense') "
            + "and transaction_date >= ? group by bank_account_id",
        tenantId, Date.valueOf(LocalDate.now().minusDays(ACTIVITY_WINDOW_DAYS)))) {
      stats.put(((Number) row.get("bank_account_id")).longValue(), row);
    }
    List<Map<String, Object>> ranking = new ArrayList<>();
    for (Map<String, Object> account : accounts) {
      Long id = ((Number) account.get("id")).longValue();
      Map<String, Object> stat = stats.get(id);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("account_id", id);
      item.put("bank_name", account.get("bank_name"));
      item.put("account_name", account.get("account_name"));
      item.put("account_no_last4", account.get("account_no_last4"));
      item.put("use_type", account.get("use_type"));
      item.put("current_balance", account.get("current_balance"));
      item.put("txn_count_90d", stat == null ? 0 : ((Number) stat.get("txn_count")).intValue());
      item.put("txn_amount_90d", stat == null ? BigDecimal.ZERO.setScale(2) : stat.get("txn_amount"));
      item.put("last_transaction_date", stat == null ? null : stat.get("last_transaction_date"));
      ranking.add(item);
    }
    ranking.sort((a, b) -> {
      int byCount = Integer.compare(((Number) b.get("txn_count_90d")).intValue(),
          ((Number) a.get("txn_count_90d")).intValue());
      if (byCount != 0) return byCount;
      return new BigDecimal(String.valueOf(b.get("txn_amount_90d")))
          .compareTo(new BigDecimal(String.valueOf(a.get("txn_amount_90d"))));
    });
    for (int i = 0; i < ranking.size(); i++) ranking.get(i).put("rank", i + 1);
    return ranking;
  }

  private Map<String, Object> balanceTrend(Long tenantId, List<Map<String, Object>> accounts, int days) {
    LocalDate end = LocalDate.now();
    LocalDate start = end.minusDays(days - 1L);
    Map<Long, Map<LocalDate, BigDecimal>> netByAccount = new HashMap<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(
        "select bank_account_id, transaction_date, coalesce(sum(case when direction in ('income', 'refund') then amount when direction in ('expense', 'reversal') then -amount else 0 end), 0) as net "
            + "from bank_transaction where tenant_id = ? and deleted_at is null and transaction_date between ? and ? "
            + "group by bank_account_id, transaction_date",
        tenantId, Date.valueOf(start), Date.valueOf(end))) {
      netByAccount.computeIfAbsent(((Number) row.get("bank_account_id")).longValue(), key -> new HashMap<>())
          .put(((Date) row.get("transaction_date")).toLocalDate(), (BigDecimal) row.get("net"));
    }
    List<Map<String, Object>> lines = new ArrayList<>();
    int limit = 0;
    for (Map<String, Object> account : accounts) {
      if (limit >= TREND_ACCOUNT_LIMIT) break;
      Long id = ((Number) account.get("id")).longValue();
      Map<LocalDate, BigDecimal> nets = netByAccount.getOrDefault(id, new HashMap<>());
      BigDecimal suffixNet = BigDecimal.ZERO;
      BigDecimal currentBalance = (BigDecimal) account.get("current_balance");
      List<Map<String, Object>> points = new ArrayList<>();
      for (int offset = days - 1; offset >= 0; offset--) {
        LocalDate date = start.plusDays(offset);
        points.add(0, point(date, currentBalance.subtract(suffixNet)));
        suffixNet = suffixNet.add(nets.getOrDefault(date, BigDecimal.ZERO));
      }
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("account_id", id);
      line.put("account_name", account.get("account_name"));
      line.put("bank_name", account.get("bank_name"));
      line.put("points", points);
      lines.add(line);
      limit++;
    }
    Map<String, Object> trend = new LinkedHashMap<>();
    trend.put("days", days);
    trend.put("start", start.toString());
    trend.put("end", end.toString());
    trend.put("account_limit", TREND_ACCOUNT_LIMIT);
    trend.put("accounts", lines);
    return trend;
  }

  private Map<String, Object> point(LocalDate date, BigDecimal balance) {
    Map<String, Object> point = new LinkedHashMap<>();
    point.put("date", date.toString());
    point.put("balance", balance);
    return point;
  }

  private List<Map<String, Object>> useTypeDistribution(Long tenantId) {
    return jdbcTemplate.queryForList(
        "select coalesce(use_type, 'unset') as use_type, count(*) as account_count, coalesce(sum(current_balance), 0) as balance "
            + "from bank_account where tenant_id = ? and deleted_at is null and status <> 'closed' "
            + "group by coalesce(use_type, 'unset') order by balance desc",
        tenantId);
  }

  private List<Map<String, Object>> closeSuggestions(Long tenantId) {
    List<Map<String, Object>> suggestions = new ArrayList<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(
        "select ba.id as account_id, ba.bank_name, ba.account_name, ba.account_no_last4, ba.current_balance, ba.use_type, "
            + "datediff('DAY', max(bt.transaction_date), current_date) as idle_days "
            + "from bank_account ba left join bank_transaction bt on bt.bank_account_id = ba.id and bt.tenant_id = ba.tenant_id and bt.deleted_at is null "
            + "where ba.tenant_id = ? and ba.deleted_at is null and ba.status = 'active' "
            + "group by ba.id, ba.bank_name, ba.account_name, ba.account_no_last4, ba.current_balance, ba.use_type",
        tenantId)) {
      Object idleDaysValue = row.get("idle_days");
      boolean noTransactions = idleDaysValue == null;
      int idleDays = noTransactions ? Integer.MAX_VALUE : ((Number) idleDaysValue).intValue();
      BigDecimal balance = (BigDecimal) row.get("current_balance");
      String useType = (String) row.get("use_type");
      boolean rigidUse = "payroll".equals(useType) || "tax".equals(useType);
      if (idleDays < CLOSE_IDLE_DAYS || balance.compareTo(CLOSE_BALANCE_THRESHOLD) >= 0 || rigidUse) continue;
      List<String> reasons = new ArrayList<>();
      reasons.add(noTransactions ? "开户后无动账记录" : "近 " + idleDays + " 天无动账");
      reasons.add("余额低于 1 万（" + balance.toPlainString() + "）");
      reasons.add(useType == null ? "未设置账户用途，无工资/税务等刚性用途" : "账户用途为 " + useType + "，无工资/税务刚性约束");
      Map<String, Object> item = new LinkedHashMap<>(row);
      item.put("reasons", reasons);
      item.put("suggestion", "建议销户");
      suggestions.add(item);
    }
    return suggestions;
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
