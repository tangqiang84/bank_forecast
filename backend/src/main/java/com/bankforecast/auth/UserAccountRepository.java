package com.bankforecast.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {

  private final JdbcTemplate jdbcTemplate;

  public UserAccountRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public UserAccount findByLoginName(String loginName) {
    List<UserAccount> users = jdbcTemplate.query(
        "select id, tenant_id, login_name, display_name, password_hash, status "
            + "from user_account where login_name = ? and deleted_at is null",
        new UserAccountRowMapper(),
        loginName);
    return users.isEmpty() ? null : users.get(0);
  }

  public UserAccount findById(Long id) {
    List<UserAccount> users = jdbcTemplate.query(
        "select id, tenant_id, login_name, display_name, password_hash, status "
            + "from user_account where id = ? and deleted_at is null",
        new UserAccountRowMapper(),
        id);
    return users.isEmpty() ? null : users.get(0);
  }

  public List<String> findRoleCodes(Long tenantId, Long userId) {
    return jdbcTemplate.queryForList(
        "select r.role_code from role r join user_role ur on ur.role_id = r.id "
            + "where ur.tenant_id = ? and ur.user_id = ? and r.status = 'active'",
        String.class,
        tenantId,
        userId);
  }

  public void updateLastLoginAt(Long userId) {
    jdbcTemplate.update(
        "update user_account set last_login_at = ?, updated_at = ? where id = ?",
        Timestamp.valueOf(LocalDateTime.now()),
        Timestamp.valueOf(LocalDateTime.now()),
        userId);
  }

  private static class UserAccountRowMapper implements RowMapper<UserAccount> {
    @Override
    public UserAccount mapRow(ResultSet rs, int rowNum) throws SQLException {
      return new UserAccount(
          rs.getLong("id"),
          rs.getLong("tenant_id"),
          rs.getString("login_name"),
          rs.getString("display_name"),
          rs.getString("password_hash"),
          rs.getString("status"));
    }
  }
}
