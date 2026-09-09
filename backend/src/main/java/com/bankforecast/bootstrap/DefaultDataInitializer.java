package com.bankforecast.bootstrap;

import java.math.BigDecimal;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import com.bankforecast.security.PasswordHashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DefaultDataInitializer implements CommandLineRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDataInitializer.class);

  private final JdbcTemplate jdbcTemplate;
  private final PasswordHashService passwordHashService;
  private final String defaultAdminPassword;

  public DefaultDataInitializer(
      JdbcTemplate jdbcTemplate,
      PasswordHashService passwordHashService,
      @Value("${bank-forecast.bootstrap.default-admin-password:}") String defaultAdminPassword) {
    this.jdbcTemplate = jdbcTemplate;
    this.passwordHashService = passwordHashService;
    this.defaultAdminPassword = defaultAdminPassword;
  }

  @Override
  public void run(String... args) {
    Integer tenantCount = jdbcTemplate.queryForObject("select count(*) from tenant", Integer.class);
    if (tenantCount != null && tenantCount > 0) {
      return;
    }

    jdbcTemplate.update(
        "insert into tenant (tenant_code, tenant_name, status) values (?, ?, ?)",
        "demo", "演示企业", "active");
    Long tenantId = jdbcTemplate.queryForObject("select id from tenant where tenant_code = ?", Long.class, "demo");

    jdbcTemplate.update(
        "insert into role (tenant_id, role_code, role_name, status) values (?, ?, ?, ?)",
        tenantId, "CFO", "财务负责人", "active");
    Long roleId = jdbcTemplate.queryForObject(
        "select id from role where tenant_id = ? and role_code = ?", Long.class, tenantId, "CFO");

    if (StringUtils.hasText(defaultAdminPassword)) {
      jdbcTemplate.update(
          "insert into user_account (tenant_id, login_name, display_name, password_hash, status) values (?, ?, ?, ?, ?)",
          tenantId, "finance01", "财务负责人", passwordHashService.hash(defaultAdminPassword), "active");
      Long userId = jdbcTemplate.queryForObject(
          "select id from user_account where tenant_id = ? and login_name = ?", Long.class, tenantId, "finance01");
      jdbcTemplate.update(
          "insert into user_role (tenant_id, user_id, role_id) values (?, ?, ?)",
          tenantId, userId, roleId);
    } else {
      LOGGER.warn("未配置默认管理员密码，已跳过初始化 finance01 登录用户");
    }

    jdbcTemplate.update(
        "insert into bank_account (tenant_id, bank_code, bank_name, account_name, account_no_cipher, account_no_last4, currency, status, current_balance) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        tenantId, "CMB", "招商银行", "演示企业基本户", "enc(local-demo-account)", "1234", "CNY", "active",
        new BigDecimal("2865300.00"));
  }
}
