package com.bankforecast.bank;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BankTransactionService {

  private final JdbcTemplate jdbcTemplate;

  public BankTransactionService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Map<String, Object> list(int page, int pageSize) {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    }
    int safePage = Math.max(page, 1);
    int safePageSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safePageSize;
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select id, bank_account_id, transaction_no, transaction_date, direction, amount, balance_after, counterparty_name, summary, match_status "
            + "from bank_transaction where tenant_id = ? and deleted_at is null order by transaction_date desc, id desc limit ? offset ?",
        principal.getTenantId(),
        safePageSize,
        offset);
    Integer total = jdbcTemplate.queryForObject(
        "select count(*) from bank_transaction where tenant_id = ? and deleted_at is null",
        Integer.class,
        principal.getTenantId());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safePageSize);
    data.put("total", total == null ? 0 : total);
    return data;
  }
}
