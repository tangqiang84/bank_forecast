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
  private static final int TIMING_WINDOW_DAYS = 35;
  private static final int MULTI_MAX_GROUP_SIZE = 4;
  private static final int MULTI_MAX_CANDIDATES = 12;

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
    int multiMatched = 0;
    int timing = 0;
    int bankUnrecorded = 0;
    int financeUnmatched = 0;

    // 第一遍：一对一（金额、方向、日期窗口一致，对手方加分）
    for (Map<String, Object> finance : finances) {
      Map<String, Object> bank = findCandidate(finance, banks, usedBankIds);
      if (bank == null) continue;
      usedBankIds.add(number(bank.get("id")));
      usedFinanceIds.add(number(finance.get("id")));
      insertReconcileResult(tenantId, jobId, bank, finance, "single", "high", "金额、方向和日期窗口一致",
          "RC-" + UUID.randomUUID().toString().replace("-", ""));
      matched++;
    }
    // 第二遍：多对多（一方合计金额等于另一方，方向、窗口和对手方一致）
    for (Map<String, Object> finance : finances) {
      if (usedFinanceIds.contains(number(finance.get("id")))) continue;
      List<Map<String, Object>> group = findBankGroup(finance, banks, usedBankIds);
      if (group == null) continue;
      String groupId = "RC-MULTI-" + UUID.randomUUID().toString().replace("-", "");
      for (Map<String, Object> bank : group) {
        usedBankIds.add(number(bank.get("id")));
        insertReconcileResult(tenantId, jobId, bank, finance, "multi", "medium", "多对多合计金额一致", groupId);
      }
      usedFinanceIds.add(number(finance.get("id")));
      multiMatched++;
    }
    for (Map<String, Object> bank : banks) {
      if (usedBankIds.contains(number(bank.get("id")))) continue;
      List<Map<String, Object>> group = findFinanceGroup(bank, finances, usedFinanceIds);
      if (group == null) continue;
      String groupId = "RC-MULTI-" + UUID.randomUUID().toString().replace("-", "");
      for (Map<String, Object> finance : group) {
        usedFinanceIds.add(number(finance.get("id")));
        insertReconcileResult(tenantId, jobId, bank, finance, "multi", "medium", "多对多合计金额一致", groupId);
      }
      usedBankIds.add(number(bank.get("id")));
      multiMatched++;
    }
    // 第三遍：跨月时间差（金额、方向、对手方一致但日期落在不同月份且不超过 35 天）
    for (Map<String, Object> finance : finances) {
      if (usedFinanceIds.contains(number(finance.get("id")))) continue;
      Map<String, Object> bank = findTimingCandidate(finance, banks, usedBankIds);
      if (bank == null) continue;
      usedBankIds.add(number(bank.get("id")));
      usedFinanceIds.add(number(finance.get("id")));
      createDifferenceException(tenantId, jobId, "timing_difference", "finance_record", number(finance.get("id")),
          "跨月时间差/在途：" + text(finance.get("record_no")),
          "财务单据 " + text(finance.get("record_no")) + "（" + text(finance.get("record_date")) + "）与银行流水 "
              + text(bank.get("transaction_no")) + "（" + text(bank.get("transaction_date")) + "）金额、方向和对手方一致但跨月，标记为时间差在途项",
          "medium");
      timing++;
    }
    for (Map<String, Object> bank : banks) {
      if (usedBankIds.contains(number(bank.get("id")))) continue;
      Map<String, Object> finance = findTimingFinanceCandidate(bank, finances, usedFinanceIds);
      if (finance == null) continue;
      usedBankIds.add(number(bank.get("id")));
      usedFinanceIds.add(number(finance.get("id")));
      createDifferenceException(tenantId, jobId, "timing_difference", "bank_transaction", number(bank.get("id")),
          "跨月时间差/在途：" + text(bank.get("transaction_no")),
          "银行流水 " + text(bank.get("transaction_no")) + "（" + text(bank.get("transaction_date")) + "）与财务单据 "
              + text(finance.get("record_no")) + "（" + text(finance.get("record_date")) + "）金额、方向和对手方一致但跨月，标记为时间差在途项",
          "medium");
      timing++;
    }
    // 剩余差异
    for (Map<String, Object> finance : finances) {
      if (usedFinanceIds.contains(number(finance.get("id")))) continue;
      financeUnmatched++;
      createDifferenceException(tenantId, jobId, "finance_unmatched", "finance_record", number(finance.get("id")),
          "财务已记账但银行未发生：" + text(finance.get("record_no")),
          "财务单据 " + text(finance.get("record_no")) + " 未找到日期、方向和金额一致的银行流水", "high");
    }
    for (Map<String, Object> bank : banks) {
      Long bankId = number(bank.get("id"));
      if (usedBankIds.contains(bankId)) continue;
      bankUnrecorded++;
      createDifferenceException(tenantId, jobId, "bank_unrecorded", "bank_transaction", bankId,
          "银行已发生但财务未记账：" + text(bank.get("transaction_no")),
          "银行流水 " + text(bank.get("transaction_no")) + " 未找到日期、方向和金额一致的财务记录", "high");
    }
    String summary = "{\"matched\":" + matched + ",\"multi_matched\":" + multiMatched
        + ",\"timing_difference\":" + timing
        + ",\"bank_unrecorded\":" + bankUnrecorded
        + ",\"finance_unmatched\":" + financeUnmatched + "}";
    finishJob(jobId, summary);
    auditService.record("RUN_FINANCE_RECONCILIATION", "match_job", String.valueOf(jobId), summary);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("job_id", jobId);
    result.put("status", "success");
    result.put("matched", matched);
    result.put("multi_matched", multiMatched);
    result.put("timing_difference", timing);
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
    String filter = " where e.tenant_id = ? and e.deleted_at is null and e.exception_type in ('bank_unrecorded', 'finance_unmatched', 'timing_difference') and (? is null or e.exception_type = ?) and (? is null or e.reconciliation_job_id = ?)";
    Object[] args = {principal.getTenantId(), differenceType, differenceType, jobId, jobId};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select e.id, e.exception_no, e.exception_type, e.source_type, e.source_id, e.title, e.description, e.status, e.severity, e.due_date, e.created_at, "
            + "bt.transaction_no, bt.transaction_date, bt.amount as bank_amount, fr.record_no, fr.record_date, fr.amount as finance_amount, fr.subject as finance_subject "
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

  private void insertReconcileResult(Long tenantId, Long jobId, Map<String, Object> bank, Map<String, Object> finance,
      String allocationMode, String confidence, String reason, String groupId) {
    jdbcTemplate.update("insert into match_result (tenant_id, match_job_id, match_group_id, allocation_mode, allocated_amount, bank_transaction_id, finance_record_id, match_type, confidence_level, match_status, match_reason) values (?, ?, ?, ?, ?, ?, ?, 'finance_reconcile', ?, 'matched', ?)",
        tenantId, jobId, groupId, allocationMode, decimal(bank.get("amount")),
        number(bank.get("id")), number(finance.get("id")), confidence, reason);
  }

  private List<Map<String, Object>> findBankGroup(Map<String, Object> finance, List<Map<String, Object>> banks, Set<Long> used) {
    String direction = expectedBankDirection(text(finance.get("record_type")));
    String counterparty = CustomerNameNormalizer.normalize(text(finance.get("counterparty_name")));
    if (counterparty.isEmpty()) return null;
    LocalDate financeDate = sqlDate(finance.get("record_date"));
    BigDecimal target = decimal(finance.get("amount"));
    List<Map<String, Object>> candidates = new ArrayList<>();
    for (Map<String, Object> bank : banks) {
      if (used.contains(number(bank.get("id"))) || !direction.equals(text(bank.get("direction")))) continue;
      BigDecimal amount = decimal(bank.get("amount"));
      if (amount.compareTo(target) >= 0) continue;
      long days = Math.abs(ChronoUnit.DAYS.between(financeDate, sqlDate(bank.get("transaction_date"))));
      if (days > DATE_WINDOW_DAYS) continue;
      if (!counterpartyMatches(counterparty, text(bank.get("counterparty_name")))) continue;
      candidates.add(bank);
      if (candidates.size() > MULTI_MAX_CANDIDATES) return null;
    }
    return sumSubset(candidates, target);
  }

  private List<Map<String, Object>> findFinanceGroup(Map<String, Object> bank, List<Map<String, Object>> finances, Set<Long> used) {
    String bankDirection = text(bank.get("direction"));
    String counterparty = CustomerNameNormalizer.normalize(text(bank.get("counterparty_name")));
    if (counterparty.isEmpty()) return null;
    LocalDate bankDate = sqlDate(bank.get("transaction_date"));
    BigDecimal target = decimal(bank.get("amount"));
    List<Map<String, Object>> candidates = new ArrayList<>();
    for (Map<String, Object> finance : finances) {
      if (used.contains(number(finance.get("id")))) continue;
      if (!bankDirection.equals(expectedBankDirection(text(finance.get("record_type"))))) continue;
      BigDecimal amount = decimal(finance.get("amount"));
      if (amount.compareTo(target) >= 0) continue;
      long days = Math.abs(ChronoUnit.DAYS.between(bankDate, sqlDate(finance.get("record_date"))));
      if (days > DATE_WINDOW_DAYS) continue;
      if (!counterpartyMatches(counterparty, text(finance.get("counterparty_name")))) continue;
      candidates.add(finance);
      if (candidates.size() > MULTI_MAX_CANDIDATES) return null;
    }
    return sumSubset(candidates, target);
  }

  private List<Map<String, Object>> sumSubset(List<Map<String, Object>> candidates, BigDecimal target) {
    return sumSubsetFrom(candidates, target, 0, new ArrayList<>(), BigDecimal.ZERO);
  }

  private List<Map<String, Object>> sumSubsetFrom(List<Map<String, Object>> candidates, BigDecimal target,
      int start, List<Map<String, Object>> chosen, BigDecimal sum) {
    if (chosen.size() >= 2 && sum.compareTo(target) == 0) return new ArrayList<>(chosen);
    if (chosen.size() >= MULTI_MAX_GROUP_SIZE || start >= candidates.size()) return null;
    for (int i = start; i < candidates.size(); i++) {
      BigDecimal next = sum.add(decimal(candidates.get(i).get("amount")));
      if (next.compareTo(target) > 0) continue;
      chosen.add(candidates.get(i));
      List<Map<String, Object>> result = sumSubsetFrom(candidates, target, i + 1, chosen, next);
      if (result != null) return result;
      chosen.remove(chosen.size() - 1);
    }
    return null;
  }

  private Map<String, Object> findTimingCandidate(Map<String, Object> finance, List<Map<String, Object>> banks, Set<Long> used) {
    String direction = expectedBankDirection(text(finance.get("record_type")));
    String counterparty = CustomerNameNormalizer.normalize(text(finance.get("counterparty_name")));
    if (counterparty.isEmpty()) return null;
    LocalDate financeDate = sqlDate(finance.get("record_date"));
    BigDecimal amount = decimal(finance.get("amount"));
    for (Map<String, Object> bank : banks) {
      if (used.contains(number(bank.get("id"))) || !direction.equals(text(bank.get("direction")))) continue;
      if (amount.compareTo(decimal(bank.get("amount"))) != 0) continue;
      if (!isCrossMonth(financeDate, sqlDate(bank.get("transaction_date")))) continue;
      if (!counterpartyMatches(counterparty, text(bank.get("counterparty_name")))) continue;
      return bank;
    }
    return null;
  }

  private Map<String, Object> findTimingFinanceCandidate(Map<String, Object> bank, List<Map<String, Object>> finances, Set<Long> used) {
    String bankDirection = text(bank.get("direction"));
    String counterparty = CustomerNameNormalizer.normalize(text(bank.get("counterparty_name")));
    if (counterparty.isEmpty()) return null;
    LocalDate bankDate = sqlDate(bank.get("transaction_date"));
    BigDecimal amount = decimal(bank.get("amount"));
    for (Map<String, Object> finance : finances) {
      if (used.contains(number(finance.get("id")))) continue;
      if (!bankDirection.equals(expectedBankDirection(text(finance.get("record_type"))))) continue;
      if (amount.compareTo(decimal(finance.get("amount"))) != 0) continue;
      if (!isCrossMonth(bankDate, sqlDate(finance.get("record_date")))) continue;
      if (!counterpartyMatches(counterparty, text(finance.get("counterparty_name")))) continue;
      return finance;
    }
    return null;
  }

  private boolean isCrossMonth(LocalDate left, LocalDate right) {
    long days = Math.abs(ChronoUnit.DAYS.between(left, right));
    boolean differentMonth = left.getYear() != right.getYear() || left.getMonth() != right.getMonth();
    return differentMonth && days <= TIMING_WINDOW_DAYS;
  }

  private boolean counterpartyMatches(String normalizedLeft, String right) {
    String normalizedRight = CustomerNameNormalizer.normalize(right);
    return !normalizedLeft.isEmpty() && !normalizedRight.isEmpty()
        && (normalizedLeft.equals(normalizedRight) || normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft));
  }

  public Map<String, Object> financeRecords(int page, int pageSize, String recordType, String subject,
      LocalDate dateFrom, LocalDate dateTo) {
    AuthPrincipal principal = requireAuth();
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safeSize;
    String type = blankToNull(recordType);
    String subjectFilter = blankToNull(subject);
    String filter = " from finance_record where tenant_id = ? and deleted_at is null"
        + " and (? is null or record_type = ?) and (? is null or subject like ?)"
        + " and (? is null or record_date >= ?) and (? is null or record_date <= ?)";
    String subjectLike = subjectFilter == null ? null : "%" + subjectFilter + "%";
    Object[] args = {principal.getTenantId(), type, type, subjectLike, subjectLike, dateFrom, dateFrom, dateTo, dateTo};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select id, record_no, record_type, record_date, posting_date, counterparty_name, amount, summary, source_system, subject, status, created_at"
            + filter + " order by record_date desc, id desc limit ? offset ?", append(args, safeSize, offset));
    Integer total = jdbcTemplate.queryForObject("select count(*)" + filter, args, Integer.class);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", total == null ? 0 : total);
    return data;
  }

  private String blankToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }

  private void createDifferenceException(Long tenantId, Long jobId, String type, String sourceType, Long sourceId, String title, String description, String severity) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from exception_case where tenant_id = ? and exception_type = ? and source_type = ? and source_id = ? and deleted_at is null", Integer.class, tenantId, type, sourceType, sourceId);
    if (count != null && count > 0) return;
    jdbcTemplate.update("insert into exception_case (tenant_id, exception_no, exception_type, source_type, source_id, reconciliation_job_id, title, description, status, severity) values (?, ?, ?, ?, ?, ?, ?, ?, 'new', ?)",
        tenantId, "EX-REC-" + UUID.randomUUID().toString().replace("-", ""), type, sourceType, sourceId, jobId, title, description, severity);
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
