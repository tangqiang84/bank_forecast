package com.bankforecast.bank;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BankTransactionService {

  private final JdbcTemplate jdbcTemplate;

  public BankTransactionService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Map<String, Object> list(int page, int pageSize, Long bankAccountId, String contractNo,
      String projectNo, LocalDate dateFrom, LocalDate dateTo, String status) {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    }
    int safePage = Math.max(page, 1);
    int safePageSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safePageSize;
    String filter = " from bank_transaction bt where bt.tenant_id = ? and bt.deleted_at is null "
        + "and (? is null or bt.bank_account_id = ?) "
        + "and (? is null or bt.transaction_date >= ?) and (? is null or bt.transaction_date <= ?) "
        + "and (? is null or bt.match_status = ?) "
        + "and (? is null or exists (select 1 from match_result mr join contract c on c.id = mr.contract_id "
        + "where mr.bank_transaction_id = bt.id and mr.deleted_at is null and c.contract_no = ?)) "
        + "and (? is null or exists (select 1 from match_result mr join project p on p.id = mr.project_id "
        + "where mr.bank_transaction_id = bt.id and mr.deleted_at is null and p.project_no = ?))";
    Object[] args = filterArgs(principal.getTenantId(), bankAccountId, dateFrom, dateTo, status, contractNo, projectNo);
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select bt.id, bt.bank_account_id, bt.transaction_no, bt.transaction_date, bt.direction, bt.amount, bt.balance_after, bt.counterparty_name, bt.summary, bt.match_status"
            + filter + " order by bt.transaction_date desc, bt.id desc limit ? offset ?",
        append(args, safePageSize, offset));
    Integer total = jdbcTemplate.queryForObject("select count(*)" + filter, args, Integer.class);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safePageSize);
    data.put("total", total == null ? 0 : total);
    return data;
  }

  public Map<String, Object> detail(Long transactionId) {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select bt.*, ba.bank_name, ba.account_name from bank_transaction bt join bank_account ba on ba.id = bt.bank_account_id "
            + "where bt.id = ? and bt.tenant_id = ? and bt.deleted_at is null", transactionId, principal.getTenantId());
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "银行流水不存在");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("transaction", rows.get(0));
    data.put("matches", jdbcTemplate.queryForList(
        "select mr.id, mr.match_type, mr.confidence_level, mr.match_status, mr.match_reason, mr.confirmed_by, mr.confirmed_at, "
            + "c.contract_no, c.contract_name, p.node_name, p.due_date, p.plan_amount, p.paid_amount "
            + "from match_result mr left join contract c on c.id = mr.contract_id left join contract_receivable_plan p on p.id = mr.contract_receivable_plan_id "
            + "where mr.bank_transaction_id = ? and mr.tenant_id = ? and mr.deleted_at is null order by mr.id desc", transactionId, principal.getTenantId()));
    data.put("audit_logs", jdbcTemplate.queryForList(
        "select id, user_id, action, target_type, target_id, trace_id, detail, created_at from audit_log "
            + "where tenant_id = ? and target_type = 'bank_transaction' and target_id = ? order by id desc", principal.getTenantId(), String.valueOf(transactionId)));
    return data;
  }

  private Object[] filterArgs(Long tenantId, Long accountId, LocalDate from, LocalDate to, String status,
      String contractNo, String projectNo) {
    return new Object[] {tenantId, accountId, accountId, from, from, to, to, status, status,
        contractNo, contractNo, projectNo, projectNo};
  }

  private Object[] append(Object[] values, Object... extra) {
    Object[] result = new Object[values.length + extra.length];
    System.arraycopy(values, 0, result, 0, values.length);
    System.arraycopy(extra, 0, result, values.length, extra.length);
    return result;
  }

  private AuthPrincipal requireAuth() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal;
  }
}
