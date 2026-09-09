package com.bankforecast.matching;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingService {
  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;

  public MatchingService(JdbcTemplate jdbcTemplate, AuditService auditService) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
  }

  @Transactional
  public Map<String, Object> runReceivableMatching() {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    Long jobId = createJob(tenantId);
    int matched = 0;
    int suggested = 0;
    int unknown = 0;

    List<Map<String, Object>> transactions = jdbcTemplate.queryForList(
        "select id, transaction_no, transaction_date, amount, counterparty_name, summary "
            + "from bank_transaction where tenant_id = ? and direction in ('income', 'refund') "
            + "and match_status = 'unmatched' and deleted_at is null order by transaction_date, id",
        tenantId);
    List<Map<String, Object>> plans = jdbcTemplate.queryForList(
        "select p.id as plan_id, p.contract_id, p.project_id, p.due_date, p.plan_amount, p.paid_amount, "
            + "c.contract_no, c.customer_name, c.contract_name from contract_receivable_plan p "
            + "join contract c on c.id = p.contract_id where p.tenant_id = ? and p.deleted_at is null "
            + "and c.deleted_at is null and p.status <> 'paid' order by p.due_date, p.id",
        tenantId);

    for (Map<String, Object> transaction : transactions) {
      MatchCandidate candidate = findCandidate(transaction, plans);
      Long transactionId = number(transaction.get("id"));
      if (candidate == null) {
        unknown++;
        createException(tenantId, "unknown_receipt", "bank_transaction", transactionId,
            "未知收款：" + text(transaction.get("counterparty_name")),
            "到账流水 " + text(transaction.get("transaction_no")) + " 未匹配到合同应收计划", "medium", null);
        continue;
      }
      String transactionStatus = candidate.partial ? "suggested" : "matched";
      insertMatchResult(tenantId, jobId, transactionId, candidate, transactionStatus);
      jdbcTemplate.update("update bank_transaction set match_status = ?, updated_at = ? where id = ? and tenant_id = ?",
          transactionStatus, Timestamp.valueOf(LocalDateTime.now()), transactionId, tenantId);
      BigDecimal newPaid = candidate.paidAmount.add(candidate.transactionAmount);
      String planStatus = newPaid.compareTo(candidate.planAmount) >= 0 ? "paid" : "partial";
      jdbcTemplate.update("update contract_receivable_plan set paid_amount = ?, status = ?, updated_at = ? where id = ? and tenant_id = ?",
          newPaid, planStatus, Timestamp.valueOf(LocalDateTime.now()), candidate.planId, tenantId);
      if (candidate.partial) {
        suggested++;
        createException(tenantId, "partial_receipt", "contract_receivable_plan", candidate.planId,
            "部分收款：" + candidate.contractName,
            "计划金额 " + candidate.planAmount + "，本次到账 " + candidate.transactionAmount + "，累计到账 " + newPaid,
            "high", candidate.dueDate);
      } else {
        matched++;
      }
      candidate.paidAmount = newPaid;
    }

    int overdue = createOverdueExceptions(tenantId);
    String summary = "{\"matched\":" + matched + ",\"suggested\":" + suggested
        + ",\"unknown\":" + unknown + ",\"overdue\":" + overdue + "}";
    finishJob(jobId, summary);
    auditService.record("RUN_RECEIVABLE_MATCH", "match_job", String.valueOf(jobId), summary);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", jobId);
    data.put("status", "success");
    data.put("matched", matched);
    data.put("suggested", suggested);
    data.put("unknown", unknown);
    data.put("overdue", overdue);
    return data;
  }

  public Map<String, Object> listResults() {
    AuthPrincipal principal = requireAuth();
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select mr.id, mr.bank_transaction_id, mr.contract_id, mr.contract_receivable_plan_id, "
            + "mr.match_type, mr.confidence_level, mr.match_status, mr.match_reason, "
            + "bt.transaction_no, bt.amount, c.contract_no, c.contract_name "
            + "from match_result mr join bank_transaction bt on bt.id = mr.bank_transaction_id "
            + "left join contract c on c.id = mr.contract_id where mr.tenant_id = ? and mr.deleted_at is null "
            + "order by mr.id desc limit 100",
        principal.getTenantId());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("total", items.size());
    return data;
  }

  public List<Map<String, Object>> listExceptions() {
    AuthPrincipal principal = requireAuth();
    return jdbcTemplate.queryForList(
        "select id, exception_no, exception_type, source_type, source_id, title, description, status, severity, due_date, created_at "
            + "from exception_case where tenant_id = ? and deleted_at is null order by id desc limit 100",
        principal.getTenantId());
  }

  private MatchCandidate findCandidate(Map<String, Object> transaction, List<Map<String, Object>> plans) {
    String summary = text(transaction.get("summary")).toLowerCase();
    String counterparty = text(transaction.get("counterparty_name")).toLowerCase();
    BigDecimal amount = decimal(transaction.get("amount"));
    LocalDate date = sqlDate(transaction.get("transaction_date"));
    MatchCandidate partial = null;
    for (Map<String, Object> plan : plans) {
      LocalDate dueDate = sqlDate(plan.get("due_date"));
      long days = Math.abs(ChronoUnit.DAYS.between(date, dueDate));
      if (days > 30) continue;
      String contractNo = text(plan.get("contract_no")).toLowerCase();
      String customer = text(plan.get("customer_name")).toLowerCase();
      boolean customerMatch = !counterparty.isEmpty() && (counterparty.contains(customer) || customer.contains(counterparty));
      boolean contractMatch = !contractNo.isEmpty() && summary.contains(contractNo);
      BigDecimal planAmount = decimal(plan.get("plan_amount"));
      BigDecimal paidAmount = decimal(plan.get("paid_amount"));
      if ((contractMatch || customerMatch) && amount.compareTo(planAmount) == 0 && days <= 7) {
        return candidate(plan, amount, "exact", "high", contractMatch ? "摘要包含合同编号且金额、日期一致" : "客户名称、金额和日期窗口一致", false);
      }
      if (partial == null && customerMatch && amount.compareTo(planAmount) < 0 && days <= 14) {
        partial = candidate(plan, amount, "partial", "medium", "客户名称一致，到账金额小于计划金额", true);
      }
    }
    return partial;
  }

  private MatchCandidate candidate(Map<String, Object> plan, BigDecimal amount, String type, String confidence,
      String reason, boolean partial) {
    MatchCandidate candidate = new MatchCandidate();
    candidate.planId = number(plan.get("plan_id"));
    candidate.contractId = number(plan.get("contract_id"));
    candidate.projectId = numberOrNull(plan.get("project_id"));
    candidate.planAmount = decimal(plan.get("plan_amount"));
    candidate.paidAmount = decimal(plan.get("paid_amount"));
    candidate.transactionAmount = amount;
    candidate.dueDate = sqlDate(plan.get("due_date"));
    candidate.contractName = text(plan.get("contract_name"));
    candidate.matchType = type;
    candidate.confidence = confidence;
    candidate.reason = reason;
    candidate.partial = partial;
    return candidate;
  }

  private void insertMatchResult(Long tenantId, Long jobId, Long transactionId, MatchCandidate candidate, String status) {
    jdbcTemplate.update(
        "insert into match_result (tenant_id, match_job_id, bank_transaction_id, contract_id, contract_receivable_plan_id, project_id, match_type, confidence_level, match_status, match_reason) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        tenantId, jobId, transactionId, candidate.contractId, candidate.planId, candidate.projectId,
        candidate.matchType, candidate.confidence, status, candidate.reason);
  }

  private int createOverdueExceptions(Long tenantId) {
    List<Map<String, Object>> plans = jdbcTemplate.queryForList(
        "select p.id, p.due_date, p.plan_amount, p.paid_amount, c.contract_name from contract_receivable_plan p "
            + "join contract c on c.id = p.contract_id where p.tenant_id = ? and p.deleted_at is null and p.status <> 'paid' and p.due_date < ?",
        tenantId, Date.valueOf(LocalDate.now()));
    int created = 0;
    for (Map<String, Object> plan : plans) {
      if (decimal(plan.get("paid_amount")).compareTo(decimal(plan.get("plan_amount"))) >= 0) continue;
      if (createException(tenantId, "unreceived", "contract_receivable_plan", number(plan.get("id")),
          "应收未收：" + text(plan.get("contract_name")),
          "应收日期 " + plan.get("due_date") + " 已到期，计划金额 " + plan.get("plan_amount") + "，累计到账 " + plan.get("paid_amount"),
          "high", sqlDate(plan.get("due_date")))) created++;
    }
    return created;
  }

  private boolean createException(Long tenantId, String type, String sourceType, Long sourceId, String title,
      String description, String severity, LocalDate dueDate) {
    try {
      jdbcTemplate.update(
          "insert into exception_case (tenant_id, exception_no, exception_type, source_type, source_id, title, description, status, severity, due_date) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          tenantId, "EXC-" + UUID.randomUUID().toString().replace("-", ""), type, sourceType, sourceId, title,
          description, "new", severity, dueDate == null ? null : Date.valueOf(dueDate));
      return true;
    } catch (DuplicateKeyException ex) {
      return false;
    }
  }

  private Long createJob(Long tenantId) {
    GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into match_job (tenant_id, job_type, scope_type, status, started_at) values (?, ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId); ps.setString(2, "receivable"); ps.setString(3, "all"); ps.setString(4, "running");
      ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now())); return ps;
    }, keyHolder);
    if (keyHolder.getKeys() != null && keyHolder.getKeys().get("id") != null) {
      return ((Number) keyHolder.getKeys().get("id")).longValue();
    }
    return keyHolder.getKey().longValue();
  }

  private void finishJob(Long jobId, String summary) {
    jdbcTemplate.update("update match_job set status = 'success', summary_json = ?, finished_at = ?, updated_at = ? where id = ?",
        summary, Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()), jobId);
  }

  private Long number(Object value) { return ((Number) value).longValue(); }
  private Long numberOrNull(Object value) { return value == null ? null : number(value); }
  private BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
  private String text(Object value) { return value == null ? "" : value.toString().trim(); }
  private LocalDate sqlDate(Object value) { return value instanceof java.sql.Date ? ((java.sql.Date) value).toLocalDate() : LocalDate.parse(text(value)); }
  private AuthPrincipal requireAuth() { AuthPrincipal p = AuthContext.get(); if (p == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录"); return p; }

  private static class MatchCandidate {
    private Long planId;
    private Long contractId;
    private Long projectId;
    private BigDecimal planAmount;
    private BigDecimal paidAmount;
    private BigDecimal transactionAmount;
    private LocalDate dueDate;
    private String contractName;
    private String matchType;
    private String confidence;
    private String reason;
    private boolean partial;
  }
}
