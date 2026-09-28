package com.bankforecast.bank;

import com.bankforecast.api.dto.TransactionClassifyRequest;
import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.common.XlsxWriter;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankTransactionService {

  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;

  public BankTransactionService(JdbcTemplate jdbcTemplate, AuditService auditService) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
  }

  public Map<String, Object> list(int page, int pageSize, Long bankAccountId, String contractNo,
      String projectNo, LocalDate dateFrom, LocalDate dateTo, String status, String keyword,
      BigDecimal amountMin, BigDecimal amountMax) {
    AuthPrincipal principal = requireAuth();
    int safePage = Math.max(page, 1);
    int safePageSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safePageSize;
    Object[] args = filterArgs(principal.getTenantId(), bankAccountId, dateFrom, dateTo, status, null,
        amountMin, amountMax, contractNo, projectNo, keyword);
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select bt.id, bt.bank_account_id, bt.transaction_no, bt.transaction_date, bt.direction, bt.amount, bt.balance_after, bt.counterparty_name, bt.summary, bt.purpose, bt.category, bt.match_status, ba.bank_name, ba.account_no_last4"
            + FILTER_SQL + " order by bt.transaction_date desc, bt.id desc limit ? offset ?",
        append(args, safePageSize, offset));
    Integer total = jdbcTemplate.queryForObject("select count(*)" + FILTER_SQL, args, Integer.class);

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
    data.put("receipts", jdbcTemplate.queryForList(
        "select id, receipt_no, transaction_date, transaction_time, currency, amount, payer_name, payee_name, "
            + "summary, verification_code, image_file_name, (image_object_key is not null) as has_image "
            + "from receipt where bank_transaction_id = ? and tenant_id = ? and deleted_at is null order by id desc",
        transactionId, principal.getTenantId()));
    return data;
  }

  @Transactional
  public Map<String, Object> classify(Long transactionId, TransactionClassifyRequest request) {
    AuthPrincipal principal = requireAuth();
    findTransaction(principal.getTenantId(), transactionId);
    jdbcTemplate.update("update bank_transaction set category = ?, purpose = ?, updated_at = current_timestamp where id = ? and tenant_id = ? and deleted_at is null",
        request.getCategory().trim(), clean(request.getPurpose()), transactionId, principal.getTenantId());
    auditService.record("CLASSIFY_BANK_TRANSACTION", "bank_transaction", String.valueOf(transactionId),
        "category=" + request.getCategory().trim() + ", purpose=" + clean(request.getPurpose()) + ", remark=" + clean(request.getRemark()));
    return findTransaction(principal.getTenantId(), transactionId);
  }

  @Transactional
  public Map<String, Object> unlink(Long transactionId, String reason) {
    AuthPrincipal principal = requireAuth();
    int[] counts = unlinkInternal(principal.getTenantId(), transactionId, reason);
    auditService.record("UNLINK_BANK_TRANSACTION", "bank_transaction", String.valueOf(transactionId), "reason=" + clean(reason) + ", groups=" + counts[0]);
    Map<String, Object> result = findTransaction(principal.getTenantId(), transactionId);
    result.put("unlinked_groups", counts[0]);
    result.put("rolled_back_plans", counts[1]);
    return result;
  }

  private int[] unlinkInternal(Long tenantId, Long transactionId, String reason) {
    findTransaction(tenantId, transactionId);
    List<Map<String, Object>> allocations = jdbcTemplate.queryForList(
        "select a.match_group_id, a.contract_receivable_plan_id, a.allocated_amount "
            + "from match_result_allocation a where a.tenant_id = ? and a.bank_transaction_id = ? "
            + "and a.status in ('matched', 'confirmed', 'manual_confirmed') and a.deleted_at is null",
        tenantId, transactionId);
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    Map<Long, BigDecimal> rollback = new LinkedHashMap<>();
    Set<String> groups = new HashSet<>();
    for (Map<String, Object> allocation : allocations) {
      Long planId = ((Number) allocation.get("contract_receivable_plan_id")).longValue();
      BigDecimal amount = new BigDecimal(String.valueOf(allocation.get("allocated_amount")));
      rollback.put(planId, rollback.containsKey(planId) ? rollback.get(planId).add(amount) : amount);
      groups.add(String.valueOf(allocation.get("match_group_id")));
    }
    for (Map.Entry<Long, BigDecimal> entry : rollback.entrySet()) {
      Map<String, Object> plan = jdbcTemplate.queryForMap("select plan_amount, paid_amount from contract_receivable_plan where id = ? and tenant_id = ?", entry.getKey(), tenantId);
      BigDecimal paid = new BigDecimal(String.valueOf(plan.get("paid_amount"))).subtract(entry.getValue()).max(BigDecimal.ZERO);
      BigDecimal planAmount = new BigDecimal(String.valueOf(plan.get("plan_amount")));
      String status = paid.compareTo(BigDecimal.ZERO) == 0 ? "unpaid" : (paid.compareTo(planAmount) >= 0 ? "paid" : "partial");
      jdbcTemplate.update("update contract_receivable_plan set paid_amount = ?, status = ?, updated_at = ? where id = ? and tenant_id = ?", paid, status, now, entry.getKey(), tenantId);
    }
    if (!groups.isEmpty()) {
      jdbcTemplate.update("update match_result_allocation set status = 'unlinked', deleted_at = ?, updated_at = ? where tenant_id = ? and bank_transaction_id = ? and deleted_at is null", now, now, tenantId, transactionId);
      jdbcTemplate.update("update match_result set match_status = 'unlinked', match_reason = concat(match_reason, '；已解除关联：', ?), updated_at = ?, deleted_at = ? where tenant_id = ? and bank_transaction_id = ? and deleted_at is null", clean(reason), now, now, tenantId, transactionId);
    }
    jdbcTemplate.update("update bank_transaction set match_status = 'unmatched', updated_at = ? where id = ? and tenant_id = ? and deleted_at is null", now, transactionId, tenantId);
    return new int[] {groups.size(), rollback.size()};
  }

  public String exportCsv(Long bankAccountId, LocalDate dateFrom, LocalDate dateTo, String status,
      String category, String keyword, BigDecimal amountMin, BigDecimal amountMax) {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> rows = exportRows(principal.getTenantId(), bankAccountId, dateFrom, dateTo,
        status, category, keyword, amountMin, amountMax);
    StringBuilder csv = new StringBuilder("transaction_no,transaction_date,direction,amount,balance_after,counterparty_name,summary,purpose,category,match_status\n");
    for (Map<String, Object> row : rows) {
      csv.append(csv(row.get("transaction_no"))).append(',').append(csv(row.get("transaction_date"))).append(',')
          .append(csv(row.get("direction"))).append(',').append(csv(row.get("amount"))).append(',').append(csv(row.get("balance_after"))).append(',')
          .append(csv(row.get("counterparty_name"))).append(',').append(csv(row.get("summary"))).append(',').append(csv(row.get("purpose"))).append(',')
          .append(csv(row.get("category"))).append(',').append(csv(row.get("match_status"))).append('\n');
    }
    return "\uFEFF" + csv;
  }

  private List<Map<String, Object>> exportRows(Long tenantId, Long bankAccountId, LocalDate dateFrom,
      LocalDate dateTo, String status, String category, String keyword, BigDecimal amountMin, BigDecimal amountMax) {
    return jdbcTemplate.queryForList(
        "select bt.transaction_no, bt.transaction_date, bt.direction, bt.amount, bt.balance_after, bt.counterparty_name, bt.summary, bt.purpose, bt.category, bt.match_status"
            + FILTER_SQL + " order by bt.transaction_date desc, bt.id desc",
        filterArgs(tenantId, bankAccountId, dateFrom, dateTo, status, category, amountMin, amountMax, null, null, keyword));
  }

  public byte[] exportXlsx(Long bankAccountId, LocalDate dateFrom, LocalDate dateTo, String status,
      String category, String keyword, BigDecimal amountMin, BigDecimal amountMax) {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> rows = exportRows(principal.getTenantId(), bankAccountId, dateFrom, dateTo,
        status, category, keyword, amountMin, amountMax);
    List<List<String>> table = new ArrayList<>();
    table.add(Arrays.asList("流水号", "交易日期", "方向", "金额", "余额", "对方户名", "摘要", "用途", "分类", "匹配状态"));
    for (Map<String, Object> row : rows) {
      table.add(Arrays.asList(
          text(row.get("transaction_no")), text(row.get("transaction_date")), text(row.get("direction")),
          text(row.get("amount")), text(row.get("balance_after")), text(row.get("counterparty_name")),
          text(row.get("summary")), text(row.get("purpose")), text(row.get("category")),
          text(row.get("match_status"))));
    }
    return XlsxWriter.write("银行流水", table);
  }

  @Transactional
  public Map<String, Object> batchClassify(List<Long> ids, String category, String purpose, String remark) {
    AuthPrincipal principal = requireAuth();
    validateBatchIds(ids);
    if (category == null || category.trim().isEmpty()) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "category 不能为空");
    }
    int updated = 0;
    for (Long id : ids) {
      updated += jdbcTemplate.update("update bank_transaction set category = ?, purpose = ?, updated_at = current_timestamp where id = ? and tenant_id = ? and deleted_at is null",
          category.trim(), clean(purpose), id, principal.getTenantId());
    }
    auditService.record("BATCH_CLASSIFY_BANK_TRANSACTION", "bank_transaction", ids.toString(),
        "category=" + category.trim() + ", remark=" + clean(remark) + ", updated=" + updated);
    return Collections.<String, Object>singletonMap("updated", updated);
  }

  @Transactional
  public Map<String, Object> batchUnlink(List<Long> ids, String reason) {
    AuthPrincipal principal = requireAuth();
    validateBatchIds(ids);
    if (reason == null || reason.trim().isEmpty()) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "reason 不能为空");
    }
    int groups = 0;
    int plans = 0;
    for (Long id : ids) {
      int[] counts = unlinkInternal(principal.getTenantId(), id, reason);
      groups += counts[0];
      plans += counts[1];
    }
    auditService.record("BATCH_UNLINK_BANK_TRANSACTION", "bank_transaction", ids.toString(),
        "reason=" + clean(reason) + ", processed=" + ids.size());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("processed", ids.size());
    result.put("unlinked_groups", groups);
    result.put("rolled_back_plans", plans);
    return result;
  }

  private void validateBatchIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "transaction_ids 不能为空");
    }
    if (ids.size() > 500) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "单次批量操作不能超过 500 条");
    }
  }

  private String text(Object value) { return value == null ? "" : String.valueOf(value); }

  private Map<String, Object> findTransaction(Long tenantId, Long transactionId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("select id, bank_account_id, transaction_no, transaction_date, direction, amount, balance_after, counterparty_name, summary, purpose, category, match_status from bank_transaction where id = ? and tenant_id = ? and deleted_at is null", transactionId, tenantId);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "银行流水不存在");
    return new LinkedHashMap<>(rows.get(0));
  }

  private String clean(String value) { return value == null ? "" : value.trim(); }
  private String csv(Object value) { String text = value == null ? "" : String.valueOf(value); return "\"" + text.replace("\"", "\"\"") + "\""; }

  private static final String FILTER_SQL =
      " from bank_transaction bt join bank_account ba on ba.id = bt.bank_account_id"
          + " where bt.tenant_id = ? and bt.deleted_at is null"
          + " and (? is null or bt.bank_account_id = ?)"
          + " and (? is null or bt.transaction_date >= ?) and (? is null or bt.transaction_date <= ?)"
          + " and (? is null or bt.match_status = ?)"
          + " and (? is null or bt.category = ?)"
          + " and (? is null or bt.amount >= ?) and (? is null or bt.amount <= ?)"
          + " and (? is null or exists (select 1 from match_result mr join contract c on c.id = mr.contract_id"
          + " where mr.bank_transaction_id = bt.id and mr.deleted_at is null and c.contract_no = ?))"
          + " and (? is null or exists (select 1 from match_result mr join project p on p.id = mr.project_id"
          + " where mr.bank_transaction_id = bt.id and mr.deleted_at is null and p.project_no = ?))"
          + " and (? is null or bt.transaction_no like ? or bt.counterparty_name like ? or bt.summary like ?"
          + " or exists (select 1 from match_result mr join contract c on c.id = mr.contract_id"
          + " where mr.bank_transaction_id = bt.id and mr.deleted_at is null and c.contract_no like ?)"
          + " or exists (select 1 from match_result mr join project p on p.id = mr.project_id"
          + " where mr.bank_transaction_id = bt.id and mr.deleted_at is null and p.project_name like ?))";

  private Object[] filterArgs(Long tenantId, Long accountId, LocalDate from, LocalDate to, String status,
      String category, BigDecimal amountMin, BigDecimal amountMax, String contractNo, String projectNo,
      String keyword) {
    String like = keyword == null || keyword.trim().isEmpty() ? null : "%" + keyword.trim() + "%";
    return new Object[] {tenantId, accountId, accountId, from, from, to, to, status, status,
        category, category, amountMin, amountMin, amountMax, amountMax,
        contractNo, contractNo, projectNo, projectNo,
        like, like, like, like, like, like};
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
