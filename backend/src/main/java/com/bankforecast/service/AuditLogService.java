package com.bankforecast.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {

  private final JdbcTemplate jdbcTemplate;

  public AuditLogService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Map<String, Object> list(Long tenantId, int page, int pageSize, String targetType, String targetId, String action) {
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safeSize;
    String filter = " where tenant_id = ? and (? is null or target_type = ?) and (? is null or target_id = ?) and (? is null or action = ?)";
    Object[] args = {tenantId, targetType, targetType, targetId, targetId, action, action};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select id, user_id, action, target_type, target_id, trace_id, detail, created_at from audit_log" + filter
            + " order by id desc limit ? offset ?", append(args, safeSize, offset));
    Integer total = jdbcTemplate.queryForObject("select count(*) from audit_log" + filter, args, Integer.class);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", total == null ? 0 : total);
    return data;
  }

  private Object[] append(Object[] values, Object... extra) {
    Object[] result = new Object[values.length + extra.length];
    System.arraycopy(values, 0, result, 0, values.length);
    System.arraycopy(extra, 0, result, values.length, extra.length);
    return result;
  }
}
