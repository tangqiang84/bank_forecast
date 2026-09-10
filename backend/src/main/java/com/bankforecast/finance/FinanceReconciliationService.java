package com.bankforecast.finance;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.matching.CustomerNameNormalizer;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceReconciliationService {
  private static final int DATE_WINDOW_DAYS = 3;

  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;

  public FinanceReconciliationService(JdbcTemplate jdbcTemplate, AuditService auditService) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
  }

  @Transactional
  public Map<String, Object> run(LocalDate dateFrom, LocalDate dateTo) {
    AuthPrincipal principal = requireAuth();
    if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "date_from 不能晚于 date_to");
    }
    Long tenantId = principal.getTenantId();
    Long jobId = createJob(tenantId);
    List<Map<String, Object>> banks = bankRows(tenantId, dateFrom, dateTo);
    List<Map<String, Object>> finances = financeRows(tenantId, dateFrom, dateTo);
    Set<Long> usedBankIds = new HashSet<>();
    Set<Long> usedFinanceIds = new HashSet<>();
    int matched = 0;
    int bankUnrecorded = 0;
    int financeUnmatched = 0;

    for (Map<String, Object> finance : finances) {
      Map<String, Object> bank = findCandidate(finance, banks, usedBankIds);
      if (bank == null) {
        financeUnmatched++;
        createDifferenceException(tenantId, jobId, "finance_unmatched", "finance_record", number(finance.get("id")),
            "财务已记账但银行未发生：" + text(finance.get("record_no")),
            "财务单据 " + text(finance.get("record_no")) + " 未找到日期、方向和金额一致的银行流水");
        continue;
      }
      usedBankIds.add(number(bank.get("id")));
      usedFinanceIds.add(number(finance.get("id")));
      insertReconcileResult(tenantId, jobId, bank, finance);
      matched++;
    }
    for (Map<String, Object> bank : banks) {
      Long bankId = number(bank.get("id"));
      if (usedBankIds.contains(bankId)) continue;
      bankUnrecorded++;
      createDifferenceException(tenantId, jobId, "bank_unrecorded", "bank_transaction", bankId,
          "银行已发生但财务未记账：" + text(bank.get("transaction_no")),
          "银行流水 " + text(bank.get("transaction_no")) + " 未找到日期、方向和金额一致的财务记录");
    }
    String summary = "{\"matched\":" + matched + ",\"bank_unrecorded\":" + bankUnrecorded
        + ",\"finance_unmatched\":" + financeUnmatched + "}";
    finishJob(jobId, summary);
    auditService.record("RUN_FINANCE_RECONCILIATION", "match_job", String.valueOf(jobId), summary);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("job_id", jobId);
    result.put("status", "success");
    result.put("matched", matched);
    result.put("bank_unrecorded", bankUnrecorded);
    result.put("finance_unmatched", financeUnmatched);
    result.put("date_from", dateFrom);
    result.put("date_to", dateTo);
    return result;
  }

  public Map<String, Object> results(Long jobId, String differenceType, int page, int pageSize) {
    AuthPrincipal principal = requireAuth();
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safeSize;
    String filter = " where e.tenant_id = ? and e.deleted_at is null and e.exception_type in ('bank_unrecorded', 'finance_unmatched') and (? is null or e.exception_type = ?) and (? is null or e.reconciliation_job_id = ?)";
    Object[] args = {principal.getTenantId(), differenceType, differenceType, jobId, jobId};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select e.id, e.exception_no, e.exception_type, e.source_type, e.source_id, e.title, e.description, e.status, e.severity, e.due_date, e.created_at, "
            + "bt.transaction_no, bt.transaction_date, bt.amount as bank_amount, fr.record_no, fr.record_date, fr.amount as finance_amount "
            + "from exception_case e left join bank_transaction bt on e.source_type = 'bank_transaction' and bt.id = e.source_id "
            + "left join finance_record fr on e.source_type = 'finance_record' and fr.id = e.source_id " + filter
            + " order by e.id desc limit ? offset ?", append(args, safeSize, offset));
    Integer total = jdbcTemplate.queryForObject("select count(*) from exception_case e" + filter, args, Integer.class);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", total == null ? 0 : total);
    if (jobId != null) data.put("job", job(tenantId(principal), jobId));
    return data;
  }

  private Map<String, Object> findCandidate(Map<String, Object> finance, List<Map<String, Object>> banks, Set<Long> used) {
    String expectedDirection = expectedBankDirection(text(finance.get("record_type")));
    LocalDate financeDate = sqlDate(finance.get("record_date"));
    BigDecimal financeAmount = decimal(finance.get("amount"));
    String financeCounterparty = CustomerNameNormalizer.normalize(text(finance.get("counterparty_name")));
    Map<String, Object> best = null;
    int bestScore = -1;
    for (Map<String, Object> bank : banks) {
      if (used.contains(number(bank.get("id"))) || !expectedDirection.equals(text(bank.get("direction")))) continue;
      if (financeAmount.compareTo(decimal(bank.get("amount"))) != 0) continue;
      long days = Math.abs(ChronoUnit.DAYS.between(financeDate, sqlDate(bank.get("transaction_date"))));
      if (days > DATE_WINDOW_DAYS) continue;
      String bankCounterparty = CustomerNameNormalizer.normalize(text(bank.get("counterparty_name")));
      boolean counterpartyMatches = !financeCounterparty.isEmpty() && !bankCounterparty.isEmpty()
          && (financeCounterparty.equals(bankCounterparty) || financeCounterparty.contains(bankCounterparty) || bankCounterparty.contains(financeCounterparty));
      int score = counterpartyMatches ? 2 : 1;
      if (score > bestScore) { bestScore = score; best = bank; }
    }
    return best;
  }

  private String expectedBankDirection(String recordType) {
    if ("receipt".equals(recordType)) return "income";
    if ("payment".equals(recordType)) return "expense";
    return "";
  }

  private void insertReconcileResult(Long tenantId, Long jobId, Map<String, Object> bank, Map<String, Object> finance) {
    jdbcTemplate.update("insert into match_result (tenant_id, match_job_id, match_group_id, allocation_mode, allocated_amount, bank_transaction_id, finance_record_id, match_type, confidence_level, match_status, match_reason) values (?, ?, ?, 'single', ?, ?, ?, 'finance_reconcile', 'high', 'matched', ?)",
        tenantId, jobId, "RC-" + UUID.randomUUID().toString().replace("-", ""), decimal(bank.get("amount")),
        number(bank.get("id")), number(finance.get("id")), "金额、方向和日期窗口一致" );
  }

  private void createDifferenceException(Long tenantId, Long jobId, String type, String sourceType, Long sourceId, String title, String description) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from exception_case where tenant_id = ? and exception_type = ? and source_type = ? and source_id = ? and deleted_at is null", Integer.class, tenantId, type, sourceType, sourceId);
    if (count != null && count > 0) return;
    jdbcTemplate.update("insert into exception_case (tenant_id, exception_no, exception_type, source_type, source_id, reconciliation_job_id, title, description, status, severity) values (?, ?, ?, ?, ?, ?, ?, ?, 'new', 'high')",
        tenantId, "EX-REC-" + UUID.randomUUID().toString().replace("-", ""), type, sourceType, sourceId, jobId, title, description);
  }

  private List<Map<String, Object>> bankRows(Long tenantId, LocalDate from, LocalDate to) {
    return jdbcTemplate.queryForList("select id, transaction_no, transaction_date, direction, amount, counterparty_name from bank_transaction where tenant_id = ? and deleted_at is null and (? is null or transaction_date >= ?) and (? is null or transaction_date <= ?) and direction in ('income', 'refund', 'expense', 'reversal') order by transaction_date, id", tenantId, from, from, to, to);
  }

  private List<Map<String, Object>> financeRows(Long tenantId, LocalDate from, LocalDate to) {
    return jdbcTemplate.queryForList("select id, record_no, record_type, record_date, amount, counterparty_name from finance_record where tenant_id = ? and status = 'active' and deleted_at is null and (? is null or record_date >= ?) and (? is null or record_date <= ?) and record_type in ('receipt', 'payment') order by record_date, id", tenantId, from, from, to, to);
  }

  private Long createJob(Long tenantId) {
    KeyHolder holder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("insert into match_job (tenant_id, job_type, scope_type, status, started_at) values (?, 'finance_reconcile', 'all', 'running', ?)", Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId); ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now())); return ps;
    }, holder);
    if (holder.getKeys() != null && holder.getKeys().get("id") != null) {
      return ((Number) holder.getKeys().get("id")).longValue();
    }
    return holder.getKey().longValue();
  }

  private void finishJob(Long jobId, String summary) { jdbcTemplate.update("update match_job set status = 'success', summary_json = ?, finished_at = ?, updated_at = ? where id = ?", summary, Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()), jobId); }

  private Map<String, Object> job(Long tenantId, Long jobId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("select id as job_id, job_type, scope_type, status, summary_json, started_at, finished_at from match_job where id = ? and tenant_id = ? and deleted_at is null", jobId, tenantId);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "对账任务不存在");
    return rows.get(0);
  }

  private Object[] append(Object[] values, Object... extra) { Object[] result = new Object[values.length + extra.length]; System.arraycopy(values, 0, result, 0, values.length); System.arraycopy(extra, 0, result, values.length, extra.length); return result; }
  private Long tenantId(AuthPrincipal principal) { return principal.getTenantId(); }
  private Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }
  private BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value)); }
  private String text(Object value) { return value == null ? "" : String.valueOf(value); }
  private LocalDate sqlDate(Object value) { return value instanceof Date ? ((Date) value).toLocalDate() : LocalDate.parse(String.valueOf(value)); }
  private AuthPrincipal requireAuth() { if (AuthContext.get() == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录"); return AuthContext.get(); }
}
