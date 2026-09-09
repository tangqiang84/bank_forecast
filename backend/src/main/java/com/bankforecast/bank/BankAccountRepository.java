package com.bankforecast.bank;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BankAccountRepository {

  private final JdbcTemplate jdbcTemplate;

  public BankAccountRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean existsByTenant(Long tenantId, Long accountId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from bank_account where tenant_id = ? and id = ? and deleted_at is null",
        Integer.class,
        tenantId,
        accountId);
    return count != null && count > 0;
  }

  public List<java.util.Map<String, Object>> listByTenant(Long tenantId) {
    return jdbcTemplate.queryForList(
        "select id, bank_code, bank_name, account_name, account_no_last4, currency, status, current_balance "
            + "from bank_account where tenant_id = ? and deleted_at is null order by id desc",
        tenantId);
  }
}
