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
import java.util.ArrayList;
import java.util.HashSet;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingService {
  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;
  private final int customerNameMinLength;
  private final boolean customerNameAllowContains;

  public MatchingService(JdbcTemplate jdbcTemplate, AuditService auditService,
      @Value("${bank-forecast.import.customer-name-min-length}") int customerNameMinLength,
      @Value("${bank-forecast.import.customer-name-allow-contains}") boolean customerNameAllowContains) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
    this.customerNameMinLength = customerNameMinLength;
    this.customerNameAllowContains = customerNameAllowContains;
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

    Set<Long> processedTransactions = new HashSet<>();
    for (Map<String, Object> transaction : transactions) {
      Long transactionId = number(transaction.get("id"));
      if (processedTransactions.contains(transactionId)) continue;
      MatchCandidate candidate = findCandidate(transaction, transactions, plans, processedTransactions);
      if (candidate == null) {
        unknown++;
        createException(tenantId, "unknown_receipt", "bank_transaction", transactionId,
            "未知收款：" + text(transaction.get("counterparty_name")),
            "到账流水 " + text(transaction.get("transaction_no")) + " 未匹配到合同应收计划", "medium", null);
        continue;
      }
      String transactionStatus = candidate.partial ? "suggested" : "matched";
      insertMatchGroup(tenantId, jobId, candidate, transactionStatus);
      processedTransactions.addAll(candidate.transactionIds());
      if (candidate.partial) {
        suggested++;
        createException(tenantId, "partial_receipt", "contract_receivable_plan", candidate.planId,
            "部分收款：" + candidate.contractName,
            "待人工确认：计划金额 " + candidate.planAmount + "，本次到账 " + candidate.transactionAmount
                + "，确认前累计到账 " + candidate.paidAmount,
            "high", candidate.dueDate);
      } else {
        matched++;
        applyFinancialEffect(tenantId, candidate.matchGroupId, transactionStatus);
        updatePlanSnapshots(plans, candidate);
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

  public Map<String, Object> listResults(int page, int pageSize, String contractNo, String projectNo,
      LocalDate dateFrom, LocalDate dateTo, String status) {
    AuthPrincipal principal = requireAuth();
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safeSize;
    String filter = " where mr.tenant_id = ? and mr.deleted_at is null "
        + "and (? is null or c.contract_no = ?) and (? is null or c.project_no = ?) "
        + "and (? is null or bt.transaction_date >= ?) and (? is null or bt.transaction_date <= ?) "
        + "and (? is null or mr.match_status = ?)";
    Object[] args = {principal.getTenantId(), contractNo, contractNo, projectNo, projectNo,
        dateFrom, dateFrom, dateTo, dateTo, status, status};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select mr.id, mr.match_group_id, mr.allocation_mode, mr.allocated_amount, "
            + "(select count(*) from match_result_allocation a where a.tenant_id = mr.tenant_id and a.match_group_id = mr.match_group_id and a.deleted_at is null) as allocation_count, "
            + "(select coalesce(sum(a.allocated_amount), 0) from match_result_allocation a where a.tenant_id = mr.tenant_id and a.match_group_id = mr.match_group_id and a.deleted_at is null) as allocation_total, "
            + "mr.bank_transaction_id, mr.contract_id, mr.contract_receivable_plan_id, "
            + "mr.match_type, mr.confidence_level, mr.match_status, mr.match_reason, mr.confirmed_by, mr.confirmed_at, "
            + "bt.transaction_no, bt.amount, bt.match_status as transaction_match_status, c.contract_no, c.contract_name, p.node_name "
            + "from match_result mr join bank_transaction bt on bt.id = mr.bank_transaction_id "
            + "left join contract c on c.id = mr.contract_id left join contract_receivable_plan p on p.id = mr.contract_receivable_plan_id "
            + filter + " order by mr.id desc limit ? offset ?", append(args, safeSize, offset));
    Integer total = jdbcTemplate.queryForObject(
        "select count(*) from match_result mr join bank_transaction bt on bt.id = mr.bank_transaction_id "
            + "left join contract c on c.id = mr.contract_id left join contract_receivable_plan p on p.id = mr.contract_receivable_plan_id " + filter,
        args, Integer.class);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", total == null ? 0 : total);
    return data;
  }

  public Map<String, Object> resultDetail(Long resultId) {
    AuthPrincipal principal = requireAuth();
    Map<String, Object> result = findResult(principal.getTenantId(), resultId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("result", result);
    data.put("transaction", jdbcTemplate.queryForMap(
        "select * from bank_transaction where id = ? and tenant_id = ? and deleted_at is null",
        result.get("bank_transaction_id"), principal.getTenantId()));
    data.put("allocations", listAllocations(principal.getTenantId(), text(result.get("match_group_id"))));
    data.put("audit_logs", jdbcTemplate.queryForList(
        "select id, user_id, action, target_type, target_id, trace_id, detail, created_at from audit_log where tenant_id = ? and target_type = 'match_result' and target_id = ? order by id desc",
        principal.getTenantId(), String.valueOf(resultId)));
    data.put("exceptions", jdbcTemplate.queryForList(
        "select id, exception_no, exception_type, title, description, status, severity, due_date, closed_at from exception_case where tenant_id = ? and source_type = 'contract_receivable_plan' and source_id = ? and deleted_at is null order by id desc",
        principal.getTenantId(), result.get("contract_receivable_plan_id")));
    return data;
  }

  public List<Map<String, Object>> resultAllocations(Long resultId) {
    AuthPrincipal principal = requireAuth();
    Map<String, Object> result = findResult(principal.getTenantId(), resultId);
    return listAllocations(principal.getTenantId(), text(result.get("match_group_id")));
  }

  @Transactional
  public Map<String, Object> confirmResult(Long resultId) {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    Map<String, Object> result = findActionableResult(tenantId, resultId);
    String groupId = text(result.get("match_group_id"));
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    int updated = jdbcTemplate.update(
        "update match_result set match_status = 'confirmed', confirmed_by = ?, confirmed_at = ?, updated_at = ? "
            + "where tenant_id = ? and match_group_id = ? and match_status = 'suggested' and deleted_at is null",
        principal.getUserId(), now, now, tenantId, groupId);
    if (updated == 0) {
      throw new BusinessException(ErrorCode.MATCH_RESULT_NOT_ACTIONABLE, "匹配结果已被处理，不能重复确认或拒绝");
    }
    jdbcTemplate.update(
        "update match_result_allocation set status = 'confirmed', updated_at = ? where tenant_id = ? and match_group_id = ? and status = 'suggested' and deleted_at is null",
        now, tenantId, groupId);
    applyFinancialEffect(tenantId, groupId, "manual_confirmed");
    closePartialExceptions(tenantId, groupId, principal, "CONFIRM", "人工确认匹配组 " + groupId);
    auditService.record("CONFIRM_MATCH_RESULT", "match_result", String.valueOf(resultId),
        "match_group_id=" + groupId + ", updated_results=" + updated);
    return resultView(tenantId, resultId);
  }

  @Transactional
  public Map<String, Object> rejectResult(Long resultId, String reason) {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    Map<String, Object> result = findActionableResult(tenantId, resultId);
    String groupId = text(result.get("match_group_id"));
    String rejectReason = reason == null || reason.trim().isEmpty() ? "未填写原因" : reason.trim();
    String matchReason = text(result.get("match_reason")) + "；人工拒绝：" + rejectReason;
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    int updated = jdbcTemplate.update(
        "update match_result set match_status = 'rejected', match_reason = ?, confirmed_by = ?, confirmed_at = ?, updated_at = ? "
            + "where tenant_id = ? and match_group_id = ? and match_status = 'suggested' and deleted_at is null",
        truncate(matchReason, 512), principal.getUserId(), now, now, tenantId, groupId);
    if (updated == 0) {
      throw new BusinessException(ErrorCode.MATCH_RESULT_NOT_ACTIONABLE, "匹配结果已被处理，不能重复确认或拒绝");
    }
    jdbcTemplate.update(
        "update match_result_allocation set status = 'rejected', updated_at = ? where tenant_id = ? and match_group_id = ? and status = 'suggested' and deleted_at is null",
        now, tenantId, groupId);
    closePartialExceptions(tenantId, groupId, principal, "REJECT", "人工拒绝匹配组 " + groupId + "：" + rejectReason);
    auditService.record("REJECT_MATCH_RESULT", "match_result", String.valueOf(resultId),
        "reason=" + rejectReason + ", match_group_id=" + groupId);
    return resultView(tenantId, resultId);
  }

  public Map<String, Object> listExceptions(int page, int pageSize, String contractNo, String projectNo,
      LocalDate dateFrom, LocalDate dateTo, String status, boolean activeOnly) {
    AuthPrincipal principal = requireAuth();
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safeSize;
    String filter = " where e.tenant_id = ? and e.deleted_at is null "
        + "and (? is null or c.contract_no = ?) and (? is null or c.project_no = ?) "
        + "and (? is null or e.due_date >= ?) and (? is null or e.due_date <= ?) "
        + "and (? is null or e.status = ?)"
        + (activeOnly ? " and e.status in ('new', 'in_progress')" : "");
    Object[] args = {principal.getTenantId(), contractNo, contractNo, projectNo, projectNo,
        dateFrom, dateFrom, dateTo, dateTo, status, status};
    String joins = " from exception_case e left join contract_receivable_plan p on e.source_type = 'contract_receivable_plan' and p.id = e.source_id "
        + "left join contract c on c.id = p.contract_id";
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select e.id, e.exception_no, e.exception_type, e.source_type, e.source_id, e.title, e.description, e.owner_user_id, e.status, e.severity, e.due_date, e.closed_at, e.created_at, e.updated_at"
            + joins + filter + " order by e.id desc limit ? offset ?", append(args, safeSize, offset));
    Integer total = jdbcTemplate.queryForObject("select count(*)" + joins + filter, args, Integer.class);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", total == null ? 0 : total);
    return data;
  }

  public Map<String, Object> exceptionDetail(Long exceptionId) {
    AuthPrincipal principal = requireAuth();
    Map<String, Object> exception = findException(principal.getTenantId(), exceptionId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("exception", exception);
    data.put("logs", listExceptionLogs(exceptionId));
    data.put("attachments", jdbcTemplate.queryForList(
        "select id, exception_case_id, file_name, content_type, file_size, uploaded_by, created_at from exception_attachment where tenant_id = ? and exception_case_id = ? and deleted_at is null order by id desc",
        principal.getTenantId(), exceptionId));
    data.put("source", jdbcTemplate.queryForList(
        "select e.source_type, e.source_id, bt.transaction_no, bt.amount, bt.transaction_date, c.contract_no, c.contract_name, p.node_name, p.due_date, p.plan_amount, p.paid_amount "
            + "from exception_case e left join bank_transaction bt on e.source_type = 'bank_transaction' and bt.id = e.source_id "
            + "left join contract_receivable_plan p on e.source_type = 'contract_receivable_plan' and p.id = e.source_id "
            + "left join contract c on c.id = p.contract_id where e.id = ? and e.tenant_id = ?", exceptionId, principal.getTenantId()));
    return data;
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

  @Transactional
  public Map<String, Object> markFalsePositive(Long exceptionId, String note) {
    AuthPrincipal principal = requireAuth();
    Long tenantId = principal.getTenantId();
    Map<String, Object> exception = findException(tenantId, exceptionId);
    ensureExceptionOpen(exception);
    String actionText = requireActionText(note);
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    jdbcTemplate.update("update exception_case set status = 'false_positive', description = ?, closed_at = ?, updated_at = ? where id = ? and tenant_id = ? and status <> 'closed'",
        truncate(text(exception.get("description")) + "\n误报原因：" + actionText, 2000), now, now, exceptionId, tenantId);
    recordExceptionAction(tenantId, exceptionId, "FALSE_POSITIVE", principal.getUserId(), actionText);
    auditService.record("FALSE_POSITIVE_EXCEPTION", "exception_case", String.valueOf(exceptionId), actionText);
    return findException(tenantId, exceptionId);
  }

  @Transactional
  public Map<String, Object> batchExceptionAction(Map<String, Object> request) {
    AuthPrincipal principal = requireAuth();
    Object idsValue = request == null ? null : request.get("exception_ids");
    String action = request == null || request.get("action") == null ? null : String.valueOf(request.get("action"));
    String text = request == null || request.get("text") == null ? null : String.valueOf(request.get("text"));
    if (!(idsValue instanceof List) || ((List<?>) idsValue).isEmpty() || action == null) throw new BusinessException(ErrorCode.PARAM_ERROR, "批量异常操作参数不完整");
    int updated = 0;
    for (Object value : (List<?>) idsValue) {
      Long id;
      try { id = Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ex) { continue; }
      if ("assign".equals(action)) { assignException(id, principal.getUserId()); updated++; }
      else if ("comment".equals(action)) { commentException(id, text); updated++; }
      else if ("resolve".equals(action)) { resolveException(id, text); updated++; }
      else if ("close".equals(action)) { closeException(id, text); updated++; }
      else if ("false_positive".equals(action)) { markFalsePositive(id, text); updated++; }
      else throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的批量异常操作");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("updated", updated);
    result.put("action", action);
    return result;
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

  private MatchCandidate findCandidate(Map<String, Object> transaction, List<Map<String, Object>> transactions,
      List<Map<String, Object>> plans, Set<Long> processedTransactions) {
    MatchCandidate exact = findSingleCandidate(transaction, plans, false);
    if (exact != null && !exact.partial) return exact;
    MatchCandidate split = findSplitCandidate(transaction, plans);
    if (split != null) return split;
    MatchCandidate merge = findMergeCandidate(transaction, transactions, plans, processedTransactions);
    if (merge != null) return merge;
    return exact;
  }

  private MatchCandidate findSingleCandidate(Map<String, Object> transaction, List<Map<String, Object>> plans,
      boolean exactOnly) {
    String summary = text(transaction.get("summary")).toLowerCase();
    String counterparty = CustomerNameNormalizer.normalize(text(transaction.get("counterparty_name")));
    BigDecimal amount = decimal(transaction.get("amount"));
    LocalDate date = sqlDate(transaction.get("transaction_date"));
    MatchCandidate partial = null;
    for (Map<String, Object> plan : plans) {
      LocalDate dueDate = sqlDate(plan.get("due_date"));
      long days = Math.abs(ChronoUnit.DAYS.between(date, dueDate));
      if (days > 30) continue;
      String contractNo = text(plan.get("contract_no")).toLowerCase();
      String customer = CustomerNameNormalizer.normalize(text(plan.get("customer_name")));
      boolean customerMatch = customerNameMatches(counterparty, customer);
      boolean contractMatch = !contractNo.isEmpty() && summary.contains(contractNo);
      BigDecimal planAmount = decimal(plan.get("plan_amount"));
      BigDecimal paidAmount = decimal(plan.get("paid_amount"));
      BigDecimal remainingAmount = planAmount.subtract(paidAmount);
      if ((contractMatch || customerMatch) && amount.compareTo(remainingAmount) == 0 && days <= 7) {
        return singleCandidate(transaction, plan, amount, "single", "exact", "high",
            contractMatch ? "摘要包含合同编号且金额、日期一致" : "客户名称归一化、金额和日期窗口一致", false);
      }
      if (!exactOnly && partial == null && customerMatch && amount.compareTo(remainingAmount) < 0 && days <= 14) {
        partial = singleCandidate(transaction, plan, amount, "single", "partial", "medium", "客户名称归一化匹配，到账金额小于应收剩余金额", true);
      }
    }
    return partial;
  }

  private MatchCandidate findSplitCandidate(Map<String, Object> transaction, List<Map<String, Object>> plans) {
    String summary = text(transaction.get("summary")).toLowerCase();
    String counterparty = CustomerNameNormalizer.normalize(text(transaction.get("counterparty_name")));
    BigDecimal remaining = decimal(transaction.get("amount"));
    LocalDate date = sqlDate(transaction.get("transaction_date"));
    List<MatchAllocation> allocations = new ArrayList<>();
    for (Map<String, Object> plan : plans) {
      if (!candidatePlanMatches(transaction, summary, counterparty, date, plan, 14)) continue;
      BigDecimal planRemaining = decimal(plan.get("plan_amount")).subtract(decimal(plan.get("paid_amount")));
      if (planRemaining.compareTo(BigDecimal.ZERO) <= 0 || planRemaining.compareTo(remaining) > 0) continue;
      allocations.add(allocation(number(transaction.get("id")), plan, planRemaining));
      remaining = remaining.subtract(planRemaining);
      if (remaining.compareTo(BigDecimal.ZERO) == 0 && allocations.size() > 1) {
        return groupCandidate(allocations, "split", "split", "high", "一笔银行流水金额等于多个应收节点剩余金额之和", false);
      }
    }
    return null;
  }

  private MatchCandidate findMergeCandidate(Map<String, Object> transaction, List<Map<String, Object>> transactions,
      List<Map<String, Object>> plans, Set<Long> processedTransactions) {
    MatchCandidate single = findSingleCandidate(transaction, plans, true);
    if (single != null) return null;
    for (Map<String, Object> plan : plans) {
      BigDecimal planRemaining = decimal(plan.get("plan_amount")).subtract(decimal(plan.get("paid_amount")));
      if (planRemaining.compareTo(BigDecimal.ZERO) <= 0) continue;
      BigDecimal sum = BigDecimal.ZERO;
      List<MatchAllocation> allocations = new ArrayList<>();
      for (Map<String, Object> current : transactions) {
        Long currentId = number(current.get("id"));
        if (processedTransactions.contains(currentId)) continue;
        if (!sameMergeScope(transaction, current)) continue;
        String summary = text(current.get("summary")).toLowerCase();
        String counterparty = CustomerNameNormalizer.normalize(text(current.get("counterparty_name")));
        if (!candidatePlanMatches(current, summary, counterparty, sqlDate(current.get("transaction_date")), plan, 14)) continue;
        BigDecimal amount = decimal(current.get("amount"));
        if (sum.add(amount).compareTo(planRemaining) > 0) continue;
        allocations.add(allocation(currentId, plan, amount));
        sum = sum.add(amount);
        if (sum.compareTo(planRemaining) == 0 && allocations.size() > 1) {
          return groupCandidate(allocations, "merge", "merge", "high", "多笔银行流水金额合计等于同一应收节点剩余金额", false);
        }
      }
    }
    return null;
  }

  private boolean candidatePlanMatches(Map<String, Object> transaction, String summary, String counterparty,
      LocalDate transactionDate, Map<String, Object> plan, int dateWindowDays) {
    LocalDate dueDate = sqlDate(plan.get("due_date"));
    long days = Math.abs(ChronoUnit.DAYS.between(transactionDate, dueDate));
    if (days > dateWindowDays) return false;
    String contractNo = text(plan.get("contract_no")).toLowerCase();
    String customer = CustomerNameNormalizer.normalize(text(plan.get("customer_name")));
    boolean customerMatch = customerNameMatches(counterparty, customer);
    boolean contractMatch = !contractNo.isEmpty() && summary.contains(contractNo);
    return contractMatch || customerMatch;
  }

  private boolean sameMergeScope(Map<String, Object> seed, Map<String, Object> current) {
    String seedCounterparty = CustomerNameNormalizer.normalize(text(seed.get("counterparty_name")));
    String currentCounterparty = CustomerNameNormalizer.normalize(text(current.get("counterparty_name")));
    if (!seedCounterparty.equals(currentCounterparty)) return false;
    long days = Math.abs(ChronoUnit.DAYS.between(sqlDate(seed.get("transaction_date")), sqlDate(current.get("transaction_date"))));
    return days <= 7;
  }

  private boolean customerNameMatches(String counterparty, String customer) {
    if (counterparty.length() < customerNameMinLength || customer.length() < customerNameMinLength) return false;
    return counterparty.equals(customer) || (customerNameAllowContains
        && (counterparty.contains(customer) || customer.contains(counterparty)));
  }

  private MatchCandidate singleCandidate(Map<String, Object> transaction, Map<String, Object> plan, BigDecimal amount,
      String allocationMode, String type, String confidence, String reason, boolean partial) {
    List<MatchAllocation> allocations = new ArrayList<>();
    allocations.add(allocation(number(transaction.get("id")), plan, amount));
    return groupCandidate(allocations, allocationMode, type, confidence, reason, partial);
  }

  private MatchCandidate groupCandidate(List<MatchAllocation> allocations, String allocationMode, String type,
      String confidence, String reason, boolean partial) {
    MatchAllocation first = allocations.get(0);
    MatchCandidate candidate = new MatchCandidate();
    candidate.matchGroupId = "MG-" + UUID.randomUUID().toString().replace("-", "");
    candidate.allocationMode = allocationMode;
    candidate.allocations = allocations;
    candidate.planId = first.planId;
    candidate.contractId = first.contractId;
    candidate.projectId = first.projectId;
    candidate.planAmount = first.planAmount;
    candidate.paidAmount = first.paidAmount;
    candidate.transactionAmount = totalAllocatedAmount(allocations);
    candidate.dueDate = first.dueDate;
    candidate.contractName = first.contractName;
    candidate.matchType = type;
    candidate.confidence = confidence;
    candidate.reason = reason;
    candidate.partial = partial;
    return candidate;
  }

  private MatchAllocation allocation(Long transactionId, Map<String, Object> plan, BigDecimal amount) {
    MatchAllocation allocation = new MatchAllocation();
    allocation.transactionId = transactionId;
    allocation.planId = number(plan.get("plan_id"));
    allocation.contractId = number(plan.get("contract_id"));
    allocation.projectId = numberOrNull(plan.get("project_id"));
    allocation.planAmount = decimal(plan.get("plan_amount"));
    allocation.paidAmount = decimal(plan.get("paid_amount"));
    allocation.allocatedAmount = amount;
    allocation.dueDate = sqlDate(plan.get("due_date"));
    allocation.contractName = text(plan.get("contract_name"));
    return allocation;
  }

  private void insertMatchGroup(Long tenantId, Long jobId, MatchCandidate candidate, String status) {
    for (MatchAllocation allocation : candidate.allocations) {
      Long resultId = insertMatchResult(tenantId, jobId, candidate, allocation, status);
      jdbcTemplate.update(
          "insert into match_result_allocation (tenant_id, match_group_id, match_result_id, bank_transaction_id, contract_receivable_plan_id, allocated_amount, status) values (?, ?, ?, ?, ?, ?, ?)",
          tenantId, candidate.matchGroupId, resultId, allocation.transactionId, allocation.planId, allocation.allocatedAmount, status);
    }
  }

  private Long insertMatchResult(Long tenantId, Long jobId, MatchCandidate candidate, MatchAllocation allocation, String status) {
    GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into match_result (tenant_id, match_job_id, match_group_id, allocation_mode, allocated_amount, bank_transaction_id, contract_id, contract_receivable_plan_id, project_id, match_type, confidence_level, match_status, match_reason) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId);
      ps.setLong(2, jobId);
      ps.setString(3, candidate.matchGroupId);
      ps.setString(4, candidate.allocationMode);
      ps.setBigDecimal(5, allocation.allocatedAmount);
      ps.setLong(6, allocation.transactionId);
      ps.setLong(7, allocation.contractId);
      ps.setLong(8, allocation.planId);
      if (allocation.projectId == null) ps.setObject(9, null); else ps.setLong(9, allocation.projectId);
      ps.setString(10, candidate.matchType);
      ps.setString(11, candidate.confidence);
      ps.setString(12, status);
      ps.setString(13, candidate.reason);
      return ps;
    }, keyHolder);
    if (keyHolder.getKeys() != null && keyHolder.getKeys().get("id") != null) {
      return ((Number) keyHolder.getKeys().get("id")).longValue();
    }
    return keyHolder.getKey().longValue();
  }

  private void applyFinancialEffect(Long tenantId, String matchGroupId, String transactionStatus) {
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    List<Map<String, Object>> allocations = jdbcTemplate.queryForList(
        "select a.bank_transaction_id, a.contract_receivable_plan_id, a.allocated_amount, bt.amount as transaction_amount, p.plan_amount, p.paid_amount "
            + "from match_result_allocation a join bank_transaction bt on bt.id = a.bank_transaction_id "
            + "join contract_receivable_plan p on p.id = a.contract_receivable_plan_id "
            + "where a.tenant_id = ? and a.match_group_id = ? and a.deleted_at is null",
        tenantId, matchGroupId);
    Set<Long> transactionIds = new HashSet<>();
    Map<Long, BigDecimal> transactionAllocatedAmounts = new LinkedHashMap<>();
    Map<Long, Map<String, Object>> transactionRows = new LinkedHashMap<>();
    Map<Long, BigDecimal> planAllocatedAmounts = new LinkedHashMap<>();
    Map<Long, Map<String, Object>> planRows = new LinkedHashMap<>();
    for (Map<String, Object> allocation : allocations) {
      Long transactionId = number(allocation.get("bank_transaction_id"));
      Long planId = number(allocation.get("contract_receivable_plan_id"));
      BigDecimal allocatedAmount = decimal(allocation.get("allocated_amount"));
      BigDecimal currentTransactionAllocated = transactionAllocatedAmounts.containsKey(transactionId) ? transactionAllocatedAmounts.get(transactionId) : BigDecimal.ZERO;
      transactionAllocatedAmounts.put(transactionId, currentTransactionAllocated.add(allocatedAmount));
      transactionRows.put(transactionId, allocation);
      BigDecimal currentAllocated = planAllocatedAmounts.containsKey(planId) ? planAllocatedAmounts.get(planId) : BigDecimal.ZERO;
      planAllocatedAmounts.put(planId, currentAllocated.add(allocatedAmount));
      planRows.put(planId, allocation);
      transactionIds.add(transactionId);
    }
    for (Map.Entry<Long, BigDecimal> entry : transactionAllocatedAmounts.entrySet()) {
      BigDecimal transactionAmount = decimal(transactionRows.get(entry.getKey()).get("transaction_amount"));
      if (entry.getValue().compareTo(transactionAmount) > 0) {
        throw new BusinessException(ErrorCode.PARAM_ERROR, "匹配分配金额超过流水金额，不能计账");
      }
    }
    for (Map.Entry<Long, BigDecimal> entry : planAllocatedAmounts.entrySet()) {
      Long planId = entry.getKey();
      Map<String, Object> allocation = planRows.get(planId);
      BigDecimal planAmount = decimal(allocation.get("plan_amount"));
      BigDecimal newPaid = decimal(allocation.get("paid_amount")).add(entry.getValue());
      if (newPaid.compareTo(planAmount) > 0) {
        throw new BusinessException(ErrorCode.PARAM_ERROR, "匹配分配金额超过应收剩余金额，不能计账");
      }
      String planStatus = newPaid.compareTo(planAmount) >= 0 ? "paid" : "partial";
      jdbcTemplate.update("update contract_receivable_plan set paid_amount = ?, status = ?, updated_at = ? where id = ? and tenant_id = ?",
          newPaid, planStatus, now, planId, tenantId);
    }
    for (Long transactionId : transactionIds) {
      jdbcTemplate.update("update bank_transaction set match_status = ?, updated_at = ? where id = ? and tenant_id = ?",
          transactionStatus, now, transactionId, tenantId);
    }
  }

  private void updatePlanSnapshots(List<Map<String, Object>> plans, MatchCandidate candidate) {
    for (MatchAllocation allocation : candidate.allocations) {
      for (Map<String, Object> plan : plans) {
        if (allocation.planId.equals(number(plan.get("plan_id")))) {
          BigDecimal newPaid = decimal(plan.get("paid_amount")).add(allocation.allocatedAmount);
          plan.put("paid_amount", newPaid);
          plan.put("status", newPaid.compareTo(decimal(plan.get("plan_amount"))) >= 0 ? "paid" : "partial");
        }
      }
    }
  }

  private BigDecimal totalAllocatedAmount(List<MatchAllocation> allocations) {
    BigDecimal total = BigDecimal.ZERO;
    for (MatchAllocation allocation : allocations) total = total.add(allocation.allocatedAmount);
    return total;
  }

  private Map<String, Object> findActionableResult(Long tenantId, Long resultId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select mr.id, mr.match_group_id, mr.bank_transaction_id, mr.contract_id, mr.contract_receivable_plan_id, mr.project_id, "
            + "mr.match_type, mr.match_reason, mr.match_status, mr.allocated_amount as transaction_amount, "
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

  private Map<String, Object> findResult(Long tenantId, Long resultId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select mr.id, mr.match_group_id, mr.allocation_mode, mr.allocated_amount, mr.bank_transaction_id, mr.contract_id, mr.contract_receivable_plan_id, mr.project_id, mr.match_type, mr.confidence_level, mr.match_status, mr.match_reason, mr.confirmed_by, mr.confirmed_at, bt.transaction_no, bt.amount, bt.transaction_date, c.contract_no, c.contract_name, p.node_name, p.due_date, p.plan_amount, p.paid_amount "
            + "from match_result mr join bank_transaction bt on bt.id = mr.bank_transaction_id left join contract c on c.id = mr.contract_id left join contract_receivable_plan p on p.id = mr.contract_receivable_plan_id where mr.id = ? and mr.tenant_id = ? and mr.deleted_at is null",
        resultId, tenantId);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "匹配结果不存在");
    return rows.get(0);
  }

  private Object[] append(Object[] values, Object... extra) {
    Object[] result = new Object[values.length + extra.length];
    System.arraycopy(values, 0, result, 0, values.length);
    System.arraycopy(extra, 0, result, values.length, extra.length);
    return result;
  }

  private Map<String, Object> resultView(Long tenantId, Long resultId) {
    return jdbcTemplate.queryForMap(
        "select mr.id, mr.match_group_id, mr.allocation_mode, mr.allocated_amount, mr.bank_transaction_id, mr.contract_id, mr.contract_receivable_plan_id, mr.match_type, "
            + "mr.confidence_level, mr.match_status, mr.match_reason, mr.confirmed_by, mr.confirmed_at, "
            + "bt.transaction_no, bt.amount, bt.match_status as transaction_match_status, c.contract_no, c.contract_name, p.node_name "
            + "from match_result mr join bank_transaction bt on bt.id = mr.bank_transaction_id "
            + "left join contract c on c.id = mr.contract_id left join contract_receivable_plan p on p.id = mr.contract_receivable_plan_id "
            + "where mr.id = ? and mr.tenant_id = ? and mr.deleted_at is null",
        resultId, tenantId);
  }

  private List<Map<String, Object>> listAllocations(Long tenantId, String matchGroupId) {
    return jdbcTemplate.queryForList(
        "select a.id, a.match_group_id, a.match_result_id, a.bank_transaction_id, bt.transaction_no, bt.transaction_date, "
            + "a.contract_receivable_plan_id, c.contract_no, c.contract_name, p.node_name, p.due_date, "
            + "a.allocated_amount, a.status, a.created_at, a.updated_at "
            + "from match_result_allocation a join bank_transaction bt on bt.id = a.bank_transaction_id "
            + "join contract_receivable_plan p on p.id = a.contract_receivable_plan_id join contract c on c.id = p.contract_id "
            + "where a.tenant_id = ? and a.match_group_id = ? and a.deleted_at is null order by a.id asc",
        tenantId, matchGroupId);
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

  private void closePartialExceptions(Long tenantId, String matchGroupId, AuthPrincipal principal, String actionType, String actionText) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select distinct contract_receivable_plan_id from match_result_allocation where tenant_id = ? and match_group_id = ? and deleted_at is null",
        tenantId, matchGroupId);
    for (Map<String, Object> row : rows) {
      closePartialException(tenantId, number(row.get("contract_receivable_plan_id")), principal, actionType, actionText);
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
    private String matchGroupId;
    private String allocationMode;
    private List<MatchAllocation> allocations;
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

    private Set<Long> transactionIds() {
      Set<Long> ids = new HashSet<>();
      for (MatchAllocation allocation : allocations) ids.add(allocation.transactionId);
      return ids;
    }
  }

  private static class MatchAllocation {
    private Long transactionId;
    private Long planId;
    private Long contractId;
    private Long projectId;
    private BigDecimal planAmount;
    private BigDecimal paidAmount;
    private BigDecimal allocatedAmount;
    private LocalDate dueDate;
    private String contractName;
  }
}
