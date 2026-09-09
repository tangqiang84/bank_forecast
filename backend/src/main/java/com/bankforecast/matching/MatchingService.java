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
            + "and match_status = 'unmatched' and deleted_at is null "
            + "and not exists (select 1 from match_result mr where mr.tenant_id = bank_transaction.tenant_id "
            + "and mr.bank_transaction_id = bank_transaction.id and mr.deleted_at is null) "
            + "order by transaction_date, id",
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
      if (candidate.partial) {
        suggested++;
        createException(tenantId, "partial_receipt", "contract_receivable_plan", candidate.planId,
            "部分收款：" + candidate.contractName,
            "待人工确认：计划金额 " + candidate.planAmount + "，本次到账 " + candidate.transactionAmount
                + "，确认前累计到账 " + candidate.paidAmount,
            "high", candidate.dueDate);
      } else {
        matched++;
        applyFinancialEffect(tenantId, transactionId, candidate, transactionStatus);
        updatePlanSnapshot(plans, candidate);
      }
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
            + "mr.match_type, mr.confidence_level, mr.match_status, mr.match_reason, mr.confirmed_by, mr.confirmed_at, "
            + "bt.transaction_no, bt.amount, bt.match_status as transaction_match_status, c.contract_no, c.contract_name, p.node_name "
            + "from match_result mr join bank_transaction bt on bt.id = mr.bank_transaction_id "
            + "left join contract c on c.id = mr.contract_id left join contract_receivable_plan p on p.id = mr.contract_receivable_plan_id "
            + "where mr.tenant_id = ? and mr.deleted_at is null "
            + "order by mr.id desc limit 100",
        principal.getTenantId());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("total", items.size());
    return data;
  }

  @Transactional
  public Map<String, Object> confirmResult(Long resultId) {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    Map<String, Object> result = findActionableResult(tenantId, resultId);
    MatchCandidate candidate = candidateFromResult(result);
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    int updated = jdbcTemplate.update(
        "update match_result set match_status = 'confirmed', confirmed_by = ?, confirmed_at = ?, updated_at = ? "
            + "where id = ? and tenant_id = ? and match_status = 'suggested' and deleted_at is null",
        principal.getUserId(), now, now, resultId, tenantId);
    if (updated == 0) {
      throw new BusinessException(ErrorCode.MATCH_RESULT_NOT_ACTIONABLE, "匹配结果已被处理，不能重复确认或拒绝");
    }
    BigDecimal newPaid = applyFinancialEffect(tenantId, number(result.get("bank_transaction_id")), candidate,
        "manual_confirmed");
    closePartialException(tenantId, candidate.planId, principal, "CONFIRM", "人工确认匹配，累计到账 " + newPaid);
    auditService.record("CONFIRM_MATCH_RESULT", "match_result", String.valueOf(resultId),
        "plan_id=" + candidate.planId + ", transaction_id=" + result.get("bank_transaction_id"));
    return resultView(tenantId, resultId);
  }

  @Transactional
  public Map<String, Object> rejectResult(Long resultId, String reason) {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    Map<String, Object> result = findActionableResult(tenantId, resultId);
    String rejectReason = reason == null || reason.trim().isEmpty() ? "未填写原因" : reason.trim();
    String matchReason = text(result.get("match_reason")) + "；人工拒绝：" + rejectReason;
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    int updated = jdbcTemplate.update(
        "update match_result set match_status = 'rejected', match_reason = ?, confirmed_by = ?, confirmed_at = ?, updated_at = ? "
            + "where id = ? and tenant_id = ? and match_status = 'suggested' and deleted_at is null",
        truncate(matchReason, 512), principal.getUserId(), now, now, resultId, tenantId);
    if (updated == 0) {
      throw new BusinessException(ErrorCode.MATCH_RESULT_NOT_ACTIONABLE, "匹配结果已被处理，不能重复确认或拒绝");
    }
    closePartialException(tenantId, number(result.get("contract_receivable_plan_id")), principal, "REJECT",
        "人工拒绝匹配：" + rejectReason);
    auditService.record("REJECT_MATCH_RESULT", "match_result", String.valueOf(resultId),
        "reason=" + rejectReason + ", transaction_id=" + result.get("bank_transaction_id"));
    return resultView(tenantId, resultId);
  }

  public List<Map<String, Object>> listExceptions() {
    AuthPrincipal principal = requireAuth();
    return jdbcTemplate.queryForList(
        "select id, exception_no, exception_type, source_type, source_id, title, description, owner_user_id, status, severity, due_date, closed_at, created_at, updated_at "
            + "from exception_case where tenant_id = ? and deleted_at is null order by id desc limit 100",
        principal.getTenantId());
  }

  @Transactional
  public Map<String, Object> assignException(Long exceptionId, Long ownerUserId) {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    Map<String, Object> exception = findException(tenantId, exceptionId);
    ensureExceptionOpen(exception);
    Long assignee = ownerUserId == null ? principal.getUserId() : ownerUserId;
    Integer activeUsers = jdbcTemplate.queryForObject(
        "select count(*) from user_account where id = ? and tenant_id = ? and status = 'active'",
        Integer.class, assignee, tenantId);
    if (activeUsers == null || activeUsers == 0) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "指定的处理人不存在或已停用");
    }
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    jdbcTemplate.update("update exception_case set owner_user_id = ?, status = case when status = 'new' then 'in_progress' else status end, updated_at = ? where id = ? and tenant_id = ?",
        assignee, now, exceptionId, tenantId);
    String actionText = "分派给用户 " + assignee;
    recordExceptionAction(tenantId, exceptionId, "ASSIGN", principal.getUserId(), actionText);
    auditService.record("ASSIGN_EXCEPTION", "exception_case", String.valueOf(exceptionId), actionText);
    return findException(tenantId, exceptionId);
  }

  @Transactional
  public Map<String, Object> commentException(Long exceptionId, String text) {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    Map<String, Object> exception = findException(tenantId, exceptionId);
    ensureExceptionOpen(exception);
    String comment = requireActionText(text);
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    jdbcTemplate.update("update exception_case set status = case when status = 'new' then 'in_progress' else status end, description = ?, updated_at = ? where id = ? and tenant_id = ?",
        truncate(text(exception.get("description")) + "\n备注：" + comment, 2000), now, exceptionId, tenantId);
    recordExceptionAction(tenantId, exceptionId, "COMMENT", principal.getUserId(), comment);
    auditService.record("COMMENT_EXCEPTION", "exception_case", String.valueOf(exceptionId), comment);
    return findException(tenantId, exceptionId);
  }

  @Transactional
  public Map<String, Object> resolveException(Long exceptionId, String note) {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    Map<String, Object> exception = findException(tenantId, exceptionId);
    ensureExceptionOpen(exception);
    String actionText = note == null || note.trim().isEmpty() ? "已处理异常" : note.trim();
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    jdbcTemplate.update("update exception_case set status = 'resolved', description = ?, updated_at = ? where id = ? and tenant_id = ? and status in ('new', 'in_progress')",
        truncate(text(exception.get("description")) + "\n处理说明：" + actionText, 2000), now, exceptionId, tenantId);
    recordExceptionAction(tenantId, exceptionId, "RESOLVE", principal.getUserId(), actionText);
    auditService.record("RESOLVE_EXCEPTION", "exception_case", String.valueOf(exceptionId), actionText);
    return findException(tenantId, exceptionId);
  }

  @Transactional
  public Map<String, Object> closeException(Long exceptionId, String note) {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    Map<String, Object> exception = findException(tenantId, exceptionId);
    if ("closed".equals(text(exception.get("status")))) {
      throw new BusinessException(ErrorCode.EXCEPTION_NOT_ACTIONABLE, "异常事项已关闭，不能重复关闭");
    }
    if (!"resolved".equals(text(exception.get("status")))) {
      throw new BusinessException(ErrorCode.EXCEPTION_NOT_ACTIONABLE, "异常事项需先处理完成，再执行关闭");
    }
    String actionText = note == null || note.trim().isEmpty() ? "关闭异常事项" : note.trim();
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    jdbcTemplate.update("update exception_case set status = 'closed', description = ?, closed_at = ?, updated_at = ? where id = ? and tenant_id = ? and status = 'resolved'",
        truncate(text(exception.get("description")) + "\n关闭说明：" + actionText, 2000), now, now, exceptionId, tenantId);
    recordExceptionAction(tenantId, exceptionId, "CLOSE", principal.getUserId(), actionText);
    auditService.record("CLOSE_EXCEPTION", "exception_case", String.valueOf(exceptionId), actionText);
    return findException(tenantId, exceptionId);
  }

  public List<Map<String, Object>> listExceptionLogs(Long exceptionId) {
    AuthPrincipal principal = requireAuth();
    findException(principal.getTenantId(), exceptionId);
    return jdbcTemplate.queryForList(
        "select id, exception_case_id, action_type, action_by, action_text, action_at from exception_action_log "
            + "where tenant_id = ? and exception_case_id = ? order by id asc",
        principal.getTenantId(), exceptionId);
  }

  private Map<String, Object> findException(Long tenantId, Long exceptionId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select id, exception_no, exception_type, source_type, source_id, title, description, owner_user_id, status, severity, due_date, closed_at, created_at, updated_at "
            + "from exception_case where id = ? and tenant_id = ? and deleted_at is null",
        exceptionId, tenantId);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "异常事项不存在");
    return rows.get(0);
  }

  private void ensureExceptionOpen(Map<String, Object> exception) {
    if ("closed".equals(text(exception.get("status")))) {
      throw new BusinessException(ErrorCode.EXCEPTION_NOT_ACTIONABLE, "异常事项已关闭，不能继续处理");
    }
  }

  private String requireActionText(String value) {
    if (value == null || value.trim().isEmpty()) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "备注内容不能为空");
    }
    return value.trim();
  }

  private void recordExceptionAction(Long tenantId, Long exceptionId, String actionType, Long userId, String actionText) {
    jdbcTemplate.update(
        "insert into exception_action_log (tenant_id, exception_case_id, action_type, action_by, action_text) values (?, ?, ?, ?, ?)",
        tenantId, exceptionId, actionType, userId, actionText);
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

  private BigDecimal applyFinancialEffect(Long tenantId, Long transactionId, MatchCandidate candidate, String transactionStatus) {
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    jdbcTemplate.update("update bank_transaction set match_status = ?, updated_at = ? where id = ? and tenant_id = ?",
        transactionStatus, now, transactionId, tenantId);
    BigDecimal newPaid = candidate.paidAmount.add(candidate.transactionAmount);
    String planStatus = newPaid.compareTo(candidate.planAmount) >= 0 ? "paid" : "partial";
    jdbcTemplate.update("update contract_receivable_plan set paid_amount = ?, status = ?, updated_at = ? where id = ? and tenant_id = ?",
        newPaid, planStatus, now, candidate.planId, tenantId);
    candidate.paidAmount = newPaid;
    return newPaid;
  }

  private void updatePlanSnapshot(List<Map<String, Object>> plans, MatchCandidate candidate) {
    for (Map<String, Object> plan : plans) {
      if (candidate.planId.equals(number(plan.get("plan_id")))) {
        plan.put("paid_amount", candidate.paidAmount);
        plan.put("status", candidate.paidAmount.compareTo(candidate.planAmount) >= 0 ? "paid" : "partial");
        return;
      }
    }
  }

  private Map<String, Object> findActionableResult(Long tenantId, Long resultId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select mr.id, mr.bank_transaction_id, mr.contract_id, mr.contract_receivable_plan_id, mr.project_id, "
            + "mr.match_type, mr.match_reason, mr.match_status, bt.amount as transaction_amount, "
            + "p.plan_amount, p.paid_amount, p.due_date, c.contract_name "
            + "from match_result mr join bank_transaction bt on bt.id = mr.bank_transaction_id "
            + "join contract_receivable_plan p on p.id = mr.contract_receivable_plan_id "
            + "join contract c on c.id = mr.contract_id where mr.id = ? and mr.tenant_id = ? "
            + "and mr.deleted_at is null and bt.tenant_id = ? and p.tenant_id = ? and c.tenant_id = ?",
        resultId, tenantId, tenantId, tenantId, tenantId);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "匹配结果不存在");
    Map<String, Object> row = rows.get(0);
    if (!"suggested".equals(text(row.get("match_status")))) {
      throw new BusinessException(ErrorCode.MATCH_RESULT_NOT_ACTIONABLE, "只有待确认的匹配结果可以人工处理");
    }
    return row;
  }

  private MatchCandidate candidateFromResult(Map<String, Object> result) {
    MatchCandidate candidate = new MatchCandidate();
    candidate.planId = number(result.get("contract_receivable_plan_id"));
    candidate.contractId = number(result.get("contract_id"));
    candidate.projectId = numberOrNull(result.get("project_id"));
    candidate.planAmount = decimal(result.get("plan_amount"));
    candidate.paidAmount = decimal(result.get("paid_amount"));
    candidate.transactionAmount = decimal(result.get("transaction_amount"));
    candidate.dueDate = sqlDate(result.get("due_date"));
    candidate.contractName = text(result.get("contract_name"));
    return candidate;
  }

  private Map<String, Object> resultView(Long tenantId, Long resultId) {
    return jdbcTemplate.queryForMap(
        "select mr.id, mr.bank_transaction_id, mr.contract_id, mr.contract_receivable_plan_id, mr.match_type, "
            + "mr.confidence_level, mr.match_status, mr.match_reason, mr.confirmed_by, mr.confirmed_at, "
            + "bt.transaction_no, bt.amount, bt.match_status as transaction_match_status, c.contract_no, c.contract_name, p.node_name "
            + "from match_result mr join bank_transaction bt on bt.id = mr.bank_transaction_id "
            + "left join contract c on c.id = mr.contract_id left join contract_receivable_plan p on p.id = mr.contract_receivable_plan_id "
            + "where mr.id = ? and mr.tenant_id = ? and mr.deleted_at is null",
        resultId, tenantId);
  }

  private void closePartialException(Long tenantId, Long planId, AuthPrincipal principal, String actionType, String actionText) {
    List<Map<String, Object>> exceptions = jdbcTemplate.queryForList(
        "select id, description from exception_case where tenant_id = ? and exception_type = 'partial_receipt' "
            + "and source_type = 'contract_receivable_plan' and source_id = ? and deleted_at is null",
        tenantId, planId);
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    Integer pendingResults = jdbcTemplate.queryForObject(
        "select count(*) from match_result where tenant_id = ? and contract_receivable_plan_id = ? "
            + "and match_status = 'suggested' and deleted_at is null",
        Integer.class, tenantId, planId);
    for (Map<String, Object> exception : exceptions) {
      if (pendingResults == null || pendingResults == 0) {
        jdbcTemplate.update("update exception_case set status = 'resolved', description = ?, closed_at = ?, updated_at = ? "
                + "where id = ? and tenant_id = ?",
            truncate(text(exception.get("description")) + "；" + actionText, 2000), now, now,
            number(exception.get("id")), tenantId);
      } else {
        jdbcTemplate.update("update exception_case set status = case when status = 'new' then 'in_progress' else status end, description = ?, updated_at = ? "
                + "where id = ? and tenant_id = ?",
            truncate(text(exception.get("description")) + "；" + actionText + "；仍有待确认候选", 2000), now,
            number(exception.get("id")), tenantId);
      }
      jdbcTemplate.update(
          "insert into exception_action_log (tenant_id, exception_case_id, action_type, action_by, action_text) values (?, ?, ?, ?, ?)",
          tenantId, number(exception.get("id")), actionType, principal.getUserId(), actionText);
    }
  }

  private String truncate(String value, int maxLength) {
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
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
      if ("partial_receipt".equals(type)) {
        jdbcTemplate.update(
            "update exception_case set title = ?, description = ?, status = 'new', closed_at = null, due_date = ?, updated_at = ? "
                + "where tenant_id = ? and exception_type = ? and source_type = ? and source_id = ? and deleted_at is null",
            title, description, dueDate == null ? null : Date.valueOf(dueDate), Timestamp.valueOf(LocalDateTime.now()),
            tenantId, type, sourceType, sourceId);
      }
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
