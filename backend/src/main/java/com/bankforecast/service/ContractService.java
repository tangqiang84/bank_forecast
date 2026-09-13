package com.bankforecast.service;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ContractService {
  private final JdbcTemplate jdbcTemplate;
  public ContractService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

  public Map<String, Object> list(Long tenantId, int page, int pageSize, String contractNo, String projectNo, String status) {
    int safePage = Math.max(page, 1), safeSize = Math.min(Math.max(pageSize, 1), 100), offset = (safePage - 1) * safeSize;
    String filter = " where c.tenant_id = ? and c.deleted_at is null and (? is null or c.contract_no = ?) and (? is null or c.project_no = ?) and (? is null or c.status = ?)";
    Object[] args = {tenantId, contractNo, contractNo, projectNo, projectNo, status, status};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select c.id, c.contract_no, c.contract_name, c.customer_name, c.project_no, c.project_name, c.contract_amount, coalesce(sum(p.plan_amount), 0) as receivable_amount, coalesce(sum(p.paid_amount), 0) as paid_amount from contract c left join contract_receivable_plan p on p.contract_id = c.id and p.deleted_at is null" + filter + " group by c.id, c.contract_no, c.contract_name, c.customer_name, c.project_no, c.project_name, c.contract_amount order by c.id desc limit ? offset ?", append(args, safeSize, offset));
    return page(items, safePage, safeSize, jdbcTemplate.queryForObject("select count(*) from contract c" + filter, args, Integer.class));
  }

  public Map<String, Object> receivables(Long tenantId, int page, int pageSize, String contractNo, String projectNo, LocalDate dateFrom, LocalDate dateTo, String status) {
    int safePage = Math.max(page, 1), safeSize = Math.min(Math.max(pageSize, 1), 100), offset = (safePage - 1) * safeSize;
    String filter = " where p.tenant_id = ? and p.deleted_at is null and c.deleted_at is null and (? is null or c.contract_no = ?) and (? is null or c.project_no = ?) and (? is null or p.due_date >= ?) and (? is null or p.due_date <= ?) and (? is null or p.status = ?)";
    Object[] args = {tenantId, contractNo, contractNo, projectNo, projectNo, dateFrom, dateFrom, dateTo, dateTo, status, status};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select p.id, p.contract_id, p.node_name, p.node_type, p.due_date, p.plan_amount, p.paid_amount, p.status, c.contract_no, c.contract_name, c.customer_name from contract_receivable_plan p join contract c on c.id = p.contract_id" + filter + " order by p.due_date, p.id limit ? offset ?", append(args, safeSize, offset));
    return page(items, safePage, safeSize, jdbcTemplate.queryForObject("select count(*) from contract_receivable_plan p join contract c on c.id = p.contract_id" + filter, args, Integer.class));
  }

  public Map<String, Object> detail(Long tenantId, Long id) {
    List<Map<String, Object>> contracts = jdbcTemplate.queryForList("select c.*, coalesce(sum(p.plan_amount), 0) as receivable_amount, coalesce(sum(p.paid_amount), 0) as paid_amount from contract c left join contract_receivable_plan p on p.contract_id = c.id and p.deleted_at is null where c.id = ? and c.tenant_id = ? and c.deleted_at is null group by c.id", id, tenantId);
    if (contracts.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "合同不存在");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("contract", contracts.get(0));
    data.put("receivables", jdbcTemplate.queryForList("select id, node_name, node_type, due_date, plan_amount, paid_amount, status from contract_receivable_plan where contract_id = ? and tenant_id = ? and deleted_at is null order by due_date, id", id, tenantId));
    data.put("transactions", jdbcTemplate.queryForList("select distinct bt.id, bt.transaction_no, bt.transaction_date, bt.direction, bt.amount, bt.counterparty_name, bt.summary, bt.match_status from bank_transaction bt join match_result mr on mr.bank_transaction_id = bt.id where mr.contract_id = ? and mr.tenant_id = ? and mr.deleted_at is null order by bt.transaction_date desc, bt.id desc", id, tenantId));
    data.put("audit_logs", jdbcTemplate.queryForList("select id, user_id, action, target_type, target_id, trace_id, detail, created_at from audit_log where tenant_id = ? and target_type = 'contract' and target_id = ? order by id desc", tenantId, String.valueOf(id)));
    return data;
  }

  private Map<String, Object> page(List<Map<String, Object>> items, int page, int pageSize, Integer total) {
    Map<String, Object> data = new LinkedHashMap<>(); data.put("items", items); data.put("page", page); data.put("page_size", pageSize); data.put("total", total == null ? 0 : total); return data;
  }
  private Object[] append(Object[] values, Object... extra) { Object[] result = new Object[values.length + extra.length]; System.arraycopy(values, 0, result, 0, values.length); System.arraycopy(extra, 0, result, values.length, extra.length); return result; }
}
