package com.bankforecast.bootstrap;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  private static final List<String> ALL_PERMISSIONS = Arrays.asList(
      "dashboard:view",
      "account:view", "account:manage", "account:scan",
      "transaction:view", "transaction:import", "transaction:export",
      "import:view",
      "contract:view", "contract:import",
      "project:view", "project:manage", "project:rule",
      "matching:view", "matching:run", "matching:confirm",
      "exception:view", "exception:assign", "exception:handle",
      "attachment:view", "attachment:manage",
      "reconciliation:view", "reconciliation:run",
      "report:view", "report:generate", "report:download",
      "forecast:view", "forecast:run", "forecast:model",
      "audit:view");

  private static final List<String> CEO_PERMISSIONS = Arrays.asList(
      "dashboard:view", "account:view", "transaction:view", "contract:view",
      "project:view", "matching:view", "exception:view", "attachment:view",
      "reconciliation:view", "report:view", "report:download", "forecast:view");

  private static final List<String> CASHIER_PERMISSIONS = Arrays.asList(
      "dashboard:view", "account:view", "account:scan",
      "transaction:view", "transaction:import", "transaction:export", "import:view",
      "contract:view", "matching:view", "matching:confirm",
      "exception:view", "exception:handle", "attachment:view", "attachment:manage",
      "reconciliation:view", "reconciliation:run",
      "report:view", "report:generate", "report:download", "forecast:view");

  private static final List<String> BUSINESS_PERMISSIONS = Arrays.asList(
      "project:view", "contract:view", "matching:view",
      "exception:view", "exception:handle", "attachment:view", "attachment:manage");

  private static final Map<String, List<String>> ROLE_PERMISSIONS = rolePermissions();
  private static final Map<String, String> ROLE_NAMES = roleNames();
  private static final Map<String, String> DEMO_USERS = demoUsers();

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
    if (tenantCount == null || tenantCount == 0) {
      jdbcTemplate.update(
          "insert into tenant (tenant_code, tenant_name, status) values (?, ?, ?)",
          "demo", "演示企业", "active");
      Long newTenantId = jdbcTemplate.queryForObject(
          "select id from tenant where tenant_code = ?", Long.class, "demo");
      jdbcTemplate.update(
          "insert into bank_account (tenant_id, bank_code, bank_name, account_name, account_no_cipher, account_no_last4, currency, status, current_balance) "
              + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
          newTenantId, "CMB", "招商银行", "演示企业基本户", "enc(local-demo-account)", "1234", "CNY", "active",
          new BigDecimal("2865300.00"));
    }

    Long tenantId = jdbcTemplate.queryForObject(
        "select id from tenant where tenant_code = ?", Long.class, "demo");
    if (tenantId == null) return;

    ensureRoles(tenantId);
    ensureRolePermissions(tenantId);
    syncDevelopmentUsers(tenantId);
  }

  private void ensureRoles(Long tenantId) {
    for (Map.Entry<String, String> entry : ROLE_NAMES.entrySet()) {
      Integer count = jdbcTemplate.queryForObject(
          "select count(*) from role where tenant_id = ? and role_code = ?",
          Integer.class, tenantId, entry.getKey());
      if (count == null || count == 0) {
        jdbcTemplate.update(
            "insert into role (tenant_id, role_code, role_name, status) values (?, ?, ?, ?)",
            tenantId, entry.getKey(), entry.getValue(), "active");
      }
    }
  }

  private void ensureRolePermissions(Long tenantId) {
    for (Map.Entry<String, List<String>> entry : ROLE_PERMISSIONS.entrySet()) {
      Long roleId = jdbcTemplate.queryForObject(
          "select id from role where tenant_id = ? and role_code = ?",
          Long.class, tenantId, entry.getKey());
      if (roleId == null) continue;
      for (String code : entry.getValue()) {
        Long permissionId = jdbcTemplate.queryForObject(
            "select id from permission where permission_code = ?", Long.class, code);
        if (permissionId == null) {
          LOGGER.warn("权限点 {} 不存在，跳过角色 {} 的映射", code, entry.getKey());
          continue;
        }
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from role_permission where tenant_id = ? and role_id = ? and permission_id = ?",
            Integer.class, tenantId, roleId, permissionId);
        if (count == null || count == 0) {
          jdbcTemplate.update(
              "insert into role_permission (tenant_id, role_id, permission_id) values (?, ?, ?)",
              tenantId, roleId, permissionId);
        }
      }
    }
  }

  private void syncDevelopmentUsers(Long tenantId) {
    if (!StringUtils.hasText(defaultAdminPassword)) {
      LOGGER.warn("未配置默认管理员密码，已跳过初始化演示登录用户");
      return;
    }
    String passwordHash = passwordHashService.hash(defaultAdminPassword);
    for (Map.Entry<String, String> entry : DEMO_USERS.entrySet()) {
      String loginName = entry.getKey();
      String roleCode = entry.getValue();
      Long roleId = jdbcTemplate.queryForObject(
          "select id from role where tenant_id = ? and role_code = ?", Long.class, tenantId, roleCode);
      if (roleId == null) continue;
      Integer userCount = jdbcTemplate.queryForObject(
          "select count(*) from user_account where tenant_id = ? and login_name = ?",
          Integer.class, tenantId, loginName);
      Long userId;
      if (userCount != null && userCount > 0) {
        jdbcTemplate.update(
            "update user_account set password_hash = ?, status = 'active', updated_at = current_timestamp where tenant_id = ? and login_name = ?",
            passwordHash, tenantId, loginName);
        userId = jdbcTemplate.queryForObject(
            "select id from user_account where tenant_id = ? and login_name = ?",
            Long.class, tenantId, loginName);
      } else {
        jdbcTemplate.update(
            "insert into user_account (tenant_id, login_name, display_name, password_hash, status) values (?, ?, ?, ?, ?)",
            tenantId, loginName, ROLE_NAMES.get(roleCode), passwordHash, "active");
        userId = jdbcTemplate.queryForObject(
            "select id from user_account where tenant_id = ? and login_name = ?",
            Long.class, tenantId, loginName);
      }
      Integer linkCount = jdbcTemplate.queryForObject(
          "select count(*) from user_role where tenant_id = ? and user_id = ? and role_id = ?",
          Integer.class, tenantId, userId, roleId);
      if (linkCount == null || linkCount == 0) {
        jdbcTemplate.update(
            "insert into user_role (tenant_id, user_id, role_id) values (?, ?, ?)",
            tenantId, userId, roleId);
      }
    }
  }

  private static Map<String, List<String>> rolePermissions() {
    Map<String, List<String>> map = new LinkedHashMap<>();
    map.put("ADMIN", ALL_PERMISSIONS);
    map.put("CFO", ALL_PERMISSIONS);
    map.put("CEO", CEO_PERMISSIONS);
    map.put("CASHIER", CASHIER_PERMISSIONS);
    map.put("BUSINESS", BUSINESS_PERMISSIONS);
    return map;
  }

  private static Map<String, String> roleNames() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("ADMIN", "系统管理员");
    map.put("CFO", "财务负责人");
    map.put("CEO", "企业负责人");
    map.put("CASHIER", "出纳资金专员");
    map.put("BUSINESS", "业务项目负责人");
    return map;
  }

  private static Map<String, String> demoUsers() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("finance01", "CFO");
    map.put("admin01", "ADMIN");
    map.put("ceo01", "CEO");
    map.put("cashier01", "CASHIER");
    map.put("biz01", "BUSINESS");
    return map;
  }
}
