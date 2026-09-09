package com.bankforecast.api;

import com.bankforecast.common.ApiResponse;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

  private final JdbcTemplate jdbcTemplate;

  public DashboardController(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @GetMapping("/overview")
  public ApiResponse<Map<String, Object>> overview() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    }

    Long tenantId = principal.getTenantId();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("total_balance", valueOrZero(jdbcTemplate.queryForObject(
        "select coalesce(sum(current_balance), 0) from bank_account where tenant_id = ? and deleted_at is null",
        BigDecimal.class,
        tenantId)));
    data.put("yesterday_net_inflow", yesterdayNetInflow(tenantId));
    data.put("pending_exceptions", pendingExceptions(tenantId));
    data.put("idle_accounts", countAccounts(tenantId, "idle"));
    data.put("match_rate", matchRate(tenantId));
    data.put("last_sync_at", latestImportTime(tenantId));
    data.put("top_receivables", new ArrayList<Map<String, Object>>());
    data.put("recent_import_jobs", recentImportJobs(tenantId));
    data.put("key_risks", keyRisks(tenantId));
    return ApiResponse.ok(data);
  }

  private BigDecimal yesterdayNetInflow(Long tenantId) {
    LocalDate yesterday = LocalDate.now().minusDays(1);
    BigDecimal income = jdbcTemplate.queryForObject(
        "select coalesce(sum(amount), 0) from bank_transaction "
            + "where tenant_id = ? and transaction_date = ? and direction in ('income', 'refund') and deleted_at is null",
        BigDecimal.class,
        tenantId,
        yesterday);
    BigDecimal expense = jdbcTemplate.queryForObject(
        "select coalesce(sum(amount), 0) from bank_transaction "
            + "where tenant_id = ? and transaction_date = ? and direction in ('expense', 'reversal') and deleted_at is null",
        BigDecimal.class,
        tenantId,
        yesterday);
    return valueOrZero(income).subtract(valueOrZero(expense));
  }

  private double matchRate(Long tenantId) {
    Integer total = jdbcTemplate.queryForObject(
        "select count(*) from bank_transaction where tenant_id = ? and deleted_at is null",
        Integer.class,
        tenantId);
    if (total == null || total == 0) {
      return 0D;
    }
    Integer matched = jdbcTemplate.queryForObject(
        "select count(*) from bank_transaction where tenant_id = ? and deleted_at is null "
            + "and match_status in ('matched', 'manual_confirmed')",
        Integer.class,
        tenantId);
    return (matched == null ? 0D : matched) / total.doubleValue();
  }

  private String latestImportTime(Long tenantId) {
    String latest = jdbcTemplate.queryForObject(
        "select max(finished_at) from import_job where tenant_id = ? and deleted_at is null",
        String.class,
        tenantId);
    return latest;
  }

  private List<Map<String, Object>> recentImportJobs(Long tenantId) {
    return jdbcTemplate.queryForList(
        "select job_type as name, status, total_rows, success_rows, failed_rows, error_message as message "
            + "from import_job where tenant_id = ? and deleted_at is null order by id desc limit 5",
        tenantId);
  }

  private List<Map<String, Object>> keyRisks(Long tenantId) {
    List<Map<String, Object>> risks = new ArrayList<>();
    int idleAccounts = countAccounts(tenantId, "idle");
    if (idleAccounts > 0) {
      risks.add(risk("账户闲置", idleAccounts, "账户状态已标记为闲置"));
    }
    Integer unmatchedIncome = jdbcTemplate.queryForObject(
        "select count(*) from bank_transaction where tenant_id = ? and deleted_at is null "
            + "and direction in ('income', 'refund') and match_status = 'unmatched'",
        Integer.class,
        tenantId);
    if (unmatchedIncome != null && unmatchedIncome > 0) {
      risks.add(risk("未知收款", unmatchedIncome, "尚未匹配到合同或项目的到账流水"));
    }
    return risks;
  }

  private int pendingExceptions(Long tenantId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from exception_case where tenant_id = ? and status not in ('closed', 'resolved') and deleted_at is null",
        Integer.class,
        tenantId);
    return count == null ? 0 : count;
  }

  private int countAccounts(Long tenantId, String status) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from bank_account where tenant_id = ? and status = ? and deleted_at is null",
        Integer.class,
        tenantId,
        status);
    return count == null ? 0 : count;
  }

  private BigDecimal valueOrZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private Map<String, Object> risk(String title, int count, String description) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("title", title);
    item.put("count", count);
    item.put("description", description);
    return item;
  }
}
