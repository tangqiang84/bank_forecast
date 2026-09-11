package com.bankforecast.bank;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
        "select ba.id, ba.bank_code, ba.bank_name, ba.account_name, ba.account_no_last4, ba.currency, ba.status, ba.current_balance, "
            + "max(bt.transaction_date) as last_transaction_at, "
            + "case when max(bt.transaction_date) is null then null else datediff('DAY', max(bt.transaction_date), current_date) end as idle_days, "
            + "case when max(bt.transaction_date) is null or datediff('DAY', max(bt.transaction_date), current_date) < 30 then 'normal' "
            + "when datediff('DAY', max(bt.transaction_date), current_date) < 90 then 'idle_30' "
            + "when datediff('DAY', max(bt.transaction_date), current_date) < 180 then 'idle_90' else 'idle_180' end as idle_level "
            + "from bank_account ba left join bank_transaction bt on bt.bank_account_id = ba.id and bt.tenant_id = ba.tenant_id and bt.deleted_at is null "
            + "where ba.tenant_id = ? and ba.deleted_at is null group by ba.id, ba.bank_code, ba.bank_name, ba.account_name, ba.account_no_last4, ba.currency, ba.status, ba.current_balance order by ba.id desc",
        tenantId);
  }

  public Map<String, Object> listByTenant(Long tenantId, int page, int pageSize) {
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safeSize;
    String from = " from bank_account ba left join bank_transaction bt on bt.bank_account_id = ba.id and bt.tenant_id = ba.tenant_id and bt.deleted_at is null"
        + " where ba.tenant_id = ? and ba.deleted_at is null";
    String select = "select ba.id, ba.bank_code, ba.bank_name, ba.account_name, ba.account_no_last4, ba.currency, ba.status, ba.current_balance, "
        + "max(bt.transaction_date) as last_transaction_at, "
        + "case when max(bt.transaction_date) is null then null else datediff('DAY', max(bt.transaction_date), current_date) end as idle_days, "
        + "case when max(bt.transaction_date) is null or datediff('DAY', max(bt.transaction_date), current_date) < 30 then 'normal' "
        + "when datediff('DAY', max(bt.transaction_date), current_date) < 90 then 'idle_30' "
        + "when datediff('DAY', max(bt.transaction_date), current_date) < 180 then 'idle_90' else 'idle_180' end as idle_level"
        + from + " group by ba.id, ba.bank_code, ba.bank_name, ba.account_name, ba.account_no_last4, ba.currency, ba.status, ba.current_balance"
        + " order by ba.id desc limit ? offset ?";
    List<Map<String, Object>> items = jdbcTemplate.queryForList(select, tenantId, safeSize, offset);
    Integer total = jdbcTemplate.queryForObject("select count(*) from bank_account ba where ba.tenant_id = ? and ba.deleted_at is null", Integer.class, tenantId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", total == null ? 0 : total);
    return data;
  }

  public Map<String, Object> findById(Long tenantId, Long id) {
    List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
        "select ba.id, ba.bank_code, ba.bank_name, ba.account_name, ba.account_no_last4, ba.currency, ba.status, ba.current_balance, "
            + "max(bt.transaction_date) as last_transaction_at, "
            + "case when max(bt.transaction_date) is null then null else datediff('DAY', max(bt.transaction_date), current_date) end as idle_days, "
            + "case when max(bt.transaction_date) is null or datediff('DAY', max(bt.transaction_date), current_date) < 30 then 'normal' "
            + "when datediff('DAY', max(bt.transaction_date), current_date) < 90 then 'idle_30' "
            + "when datediff('DAY', max(bt.transaction_date), current_date) < 180 then 'idle_90' else 'idle_180' end as idle_level "
            + "from bank_account ba left join bank_transaction bt on bt.bank_account_id = ba.id and bt.tenant_id = ba.tenant_id and bt.deleted_at is null "
            + "where ba.tenant_id = ? and ba.id = ? and ba.deleted_at is null "
            + "group by ba.id, ba.bank_code, ba.bank_name, ba.account_name, ba.account_no_last4, ba.currency, ba.status, ba.current_balance",
        tenantId, id);
    if (accounts.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "银行账户不存在");

    Map<String, Object> data = new LinkedHashMap<>(accounts.get(0));
    data.put("transactions", jdbcTemplate.queryForList(
        "select id, transaction_no, transaction_date, direction, amount, balance_after, counterparty_name, summary, purpose, category, match_status "
            + "from bank_transaction where tenant_id = ? and bank_account_id = ? and deleted_at is null order by transaction_date desc, id desc limit 10",
        tenantId, id));
    data.put("transaction_count", jdbcTemplate.queryForObject(
        "select count(*) from bank_transaction where tenant_id = ? and bank_account_id = ? and deleted_at is null",
        Integer.class, tenantId, id));
    data.put("audit_logs", jdbcTemplate.queryForList(
        "select id, action, detail, created_at from audit_log where tenant_id = ? and target_type = 'bank_account' and target_id = ? order by id desc limit 20",
        tenantId, String.valueOf(id)));
    return data;
  }

  public Map<String, Object> create(Long tenantId, String bankCode, String bankName, String accountName,
      String accountNo, String currency, BigDecimal currentBalance) {
    jdbcTemplate.update(
        "insert into bank_account (tenant_id, bank_code, bank_name, account_name, account_no_cipher, account_no_last4, currency, status, current_balance) values (?, ?, ?, ?, ?, ?, ?, 'active', ?)",
        tenantId, bankCode, bankName, accountName, maskForStorage(accountNo), last4(accountNo), currency, currentBalance);
    Long id = jdbcTemplate.queryForObject("select max(id) from bank_account where tenant_id = ?", Long.class, tenantId);
    return find(tenantId, id);
  }

  public Map<String, Object> update(Long tenantId, Long id, String bankCode, String bankName, String accountName,
      String accountNo, String currency, BigDecimal currentBalance) {
    if (!existsByTenant(tenantId, id)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "银行账户不存在");
    jdbcTemplate.update(
        "update bank_account set bank_code = ?, bank_name = ?, account_name = ?, account_no_cipher = ?, account_no_last4 = ?, currency = ?, current_balance = ?, updated_at = current_timestamp where id = ? and tenant_id = ? and deleted_at is null",
        bankCode, bankName, accountName, maskForStorage(accountNo), last4(accountNo), currency, currentBalance, id, tenantId);
    return find(tenantId, id);
  }

  public Map<String, Object> close(Long tenantId, Long id) {
    if (!existsByTenant(tenantId, id)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "银行账户不存在");
    jdbcTemplate.update("update bank_account set status = 'closed', updated_at = current_timestamp where id = ? and tenant_id = ? and deleted_at is null", id, tenantId);
    return find(tenantId, id);
  }

  public int refreshIdleStatuses(Long tenantId) {
    return jdbcTemplate.update(
        "update bank_account ba set status = case when ba.status = 'closed' then 'closed' when (select max(bt.transaction_date) from bank_transaction bt where bt.bank_account_id = ba.id and bt.tenant_id = ba.tenant_id and bt.deleted_at is null) is null or datediff('DAY', (select max(bt2.transaction_date) from bank_transaction bt2 where bt2.bank_account_id = ba.id and bt2.tenant_id = ba.tenant_id and bt2.deleted_at is null), current_date) >= 30 then 'idle' else 'active' end, updated_at = current_timestamp where ba.tenant_id = ? and ba.deleted_at is null",
        tenantId);
  }

  private Map<String, Object> find(Long tenantId, Long id) { return findById(tenantId, id); }

  private String last4(String accountNo) {
    return accountNo.substring(Math.max(0, accountNo.length() - 4));
  }

  private String maskForStorage(String accountNo) {
    return "enc(local):" + Integer.toHexString(accountNo.hashCode()) + ":" + last4(accountNo);
  }
}
