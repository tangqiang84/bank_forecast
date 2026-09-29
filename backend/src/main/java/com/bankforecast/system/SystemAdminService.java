package com.bankforecast.system;

import com.bankforecast.audit.AuditService;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import com.bankforecast.security.PasswordHashService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemAdminService {
  private final JdbcTemplate jdbcTemplate;
  private final PasswordHashService passwordHashService;
  private final AuditService auditService;

  public SystemAdminService(JdbcTemplate jdbcTemplate, PasswordHashService passwordHashService,
      AuditService auditService) {
    this.jdbcTemplate = jdbcTemplate;
    this.passwordHashService = passwordHashService;
    this.auditService = auditService;
  }

  public Map<String, Object> listUsers(Long tenantId, int page, int pageSize, String keyword) {
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(pageSize, 1), 100);
    int offset = (safePage - 1) * safeSize;
    String like = keyword == null || keyword.trim().isEmpty() ? null : "%" + keyword.trim() + "%";
    String filter = " from user_account u where u.tenant_id = ? and u.deleted_at is null"
        + " and (? is null or u.login_name like ? or u.display_name like ?)";
    Object[] args = {tenantId, like, like, like};
    List<Map<String, Object>> items = jdbcTemplate.queryForList(
        "select u.id, u.login_name, u.display_name, u.status, u.last_login_at, u.created_at" + filter
            + " order by u.id limit ? offset ?", append(args, safeSize, offset));
    for (Map<String, Object> item : items) {
      Long userId = ((Number) item.get("id")).longValue();
      item.put("roles", jdbcTemplate.queryForList(
          "select r.role_code from user_role ur join role r on r.id = ur.role_id where ur.tenant_id = ? and ur.user_id = ? and r.status = 'active' order by r.role_code",
          String.class, tenantId, userId));
      item.put("scoped_project_count", jdbcTemplate.queryForObject(
          "select count(*) from user_project_scope where tenant_id = ? and user_id = ?",
          Integer.class, tenantId, userId));
    }
    Integer total = jdbcTemplate.queryForObject("select count(*)" + filter, args, Integer.class);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", safePage);
    data.put("page_size", safeSize);
    data.put("total", total == null ? 0 : total);
    return data;
  }

  @Transactional
  public Map<String, Object> createUser(Long tenantId, Long operatorId, Map<String, Object> request) {
    String loginName = requiredText(request, "login_name");
    String displayName = requiredText(request, "display_name");
    String password = requiredText(request, "password");
    if (password.length() < 8) throw new BusinessException(ErrorCode.PARAM_ERROR, "密码至少 8 位");
    Integer exists = jdbcTemplate.queryForObject(
        "select count(*) from user_account where tenant_id = ? and login_name = ?", Integer.class, tenantId, loginName);
    if (exists != null && exists > 0) throw new BusinessException(ErrorCode.PARAM_ERROR, "登录名已存在");
    List<String> roleCodes = roleCodes(request.get("role_codes"));
    KeyHolder holder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into user_account (tenant_id, login_name, display_name, password_hash, status) values (?, ?, ?, ?, 'active')",
          Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, tenantId);
      ps.setString(2, loginName);
      ps.setString(3, displayName);
      ps.setString(4, passwordHashService.hash(password));
      return ps;
    }, holder);
    Long userId = generatedId(holder);
    assignRoles(tenantId, userId, roleCodes);
    auditService.record("CREATE_USER", "user_account", String.valueOf(userId),
        "login_name=" + loginName + ", roles=" + roleCodes);
    return jdbcTemplate.queryForMap(
        "select id, login_name, display_name, status, created_at from user_account where id = ? and tenant_id = ?",
        userId, tenantId);
  }

  @Transactional
  public Map<String, Object> updateUser(Long tenantId, Long operatorId, Long userId, Map<String, Object> request) {
    Map<String, Object> user = findUser(tenantId, userId);
    if (userId.equals(operatorId) && "disabled".equals(text(request.get("status")))) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "不能停用当前登录账号");
    }
    String displayName = request.get("display_name") == null
        ? text(user.get("display_name")) : requiredText(request, "display_name");
    String status = request.get("status") == null ? text(user.get("status")) : text(request.get("status"));
    if (!status.equals("active") && !status.equals("disabled")) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "status 仅支持 active/disabled");
    }
    jdbcTemplate.update("update user_account set display_name = ?, status = ?, updated_at = current_timestamp where id = ? and tenant_id = ?",
        displayName, status, userId, tenantId);
    if (request.containsKey("role_codes")) {
      List<String> roleCodes = roleCodes(request.get("role_codes"));
      if (userId.equals(operatorId) && !roleCodes.contains("ADMIN")
          && userRoles(tenantId, userId).contains("ADMIN")) {
        throw new BusinessException(ErrorCode.PARAM_ERROR, "不能移除当前登录账号的管理员角色");
      }
      assignRoles(tenantId, userId, roleCodes);
    }
    auditService.record("UPDATE_USER", "user_account", String.valueOf(userId),
        "status=" + status + ", roles=" + userRoles(tenantId, userId));
    Map<String, Object> result = jdbcTemplate.queryForMap(
        "select id, login_name, display_name, status, created_at from user_account where id = ? and tenant_id = ?",
        userId, tenantId);
    result.put("roles", userRoles(tenantId, userId));
    return result;
  }

  @Transactional
  public Map<String, Object> resetPassword(Long tenantId, Long userId, Map<String, Object> request) {
    findUser(tenantId, userId);
    String password = requiredText(request, "password");
    if (password.length() < 8) throw new BusinessException(ErrorCode.PARAM_ERROR, "密码至少 8 位");
    jdbcTemplate.update("update user_account set password_hash = ?, updated_at = current_timestamp where id = ? and tenant_id = ?",
        passwordHashService.hash(password), userId, tenantId);
    auditService.record("RESET_USER_PASSWORD", "user_account", String.valueOf(userId), "");
    return java.util.Collections.singletonMap("reset", true);
  }

  public List<Map<String, Object>> listRoles(Long tenantId) {
    List<Map<String, Object>> roles = jdbcTemplate.queryForList(
        "select id, role_code, role_name, status, created_at from role where tenant_id = ? and deleted_at is null order by id",
        tenantId);
    for (Map<String, Object> role : roles) {
      role.put("permission_codes", jdbcTemplate.queryForList(
          "select p.permission_code from role_permission rp join permission p on p.id = rp.permission_id "
              + "where rp.tenant_id = ? and rp.role_id = ? and p.status = 'active' order by p.permission_code",
          String.class, tenantId, ((Number) role.get("id")).longValue()));
    }
    return roles;
  }

  @Transactional
  public Map<String, Object> updateRolePermissions(Long tenantId, Long roleId, Map<String, Object> request) {
    List<Map<String, Object>> roles = jdbcTemplate.queryForList(
        "select id, role_code from role where id = ? and tenant_id = ? and deleted_at is null", roleId, tenantId);
    if (roles.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "角色不存在");
    if ("ADMIN".equals(String.valueOf(roles.get(0).get("role_code")))) {
      throw new BusinessException(ErrorCode.PARAM_ERROR, "管理员角色的权限不可修改");
    }
    List<String> codes = roleCodes(request.get("permission_codes"));
    jdbcTemplate.update("delete from role_permission where tenant_id = ? and role_id = ?", tenantId, roleId);
    for (String code : codes) {
      Long permissionId = jdbcTemplate.queryForObject(
          "select id from permission where permission_code = ? and status = 'active'", Long.class, code);
      if (permissionId == null) throw new BusinessException(ErrorCode.PARAM_ERROR, "权限点不存在：" + code);
      jdbcTemplate.update("insert into role_permission (tenant_id, role_id, permission_id) values (?, ?, ?)",
          tenantId, roleId, permissionId);
    }
    auditService.record("UPDATE_ROLE_PERMISSIONS", "role", String.valueOf(roleId), "permissions=" + codes);
    return jdbcTemplate.queryForMap("select id, role_code, role_name, status from role where id = ?", roleId);
  }

  public List<Map<String, Object>> listPermissions() {
    return jdbcTemplate.queryForList(
        "select permission_code, permission_name, module from permission where status = 'active' order by module, permission_code");
  }

  public List<Long> projectScope(Long tenantId, Long userId) {
    findUser(tenantId, userId);
    return jdbcTemplate.queryForList(
        "select project_id from user_project_scope where tenant_id = ? and user_id = ? order by project_id",
        Long.class, tenantId, userId);
  }

  @Transactional
  public Map<String, Object> updateProjectScope(Long tenantId, Long userId, Map<String, Object> request) {
    findUser(tenantId, userId);
    Object raw = request == null ? null : request.get("project_ids");
    if (!(raw instanceof List)) throw new BusinessException(ErrorCode.PARAM_ERROR, "project_ids 必须是数组");
    List<Long> projectIds = new ArrayList<>();
    for (Object item : (List<?>) raw) {
      Long projectId = Long.valueOf(String.valueOf(item));
      Integer exists = jdbcTemplate.queryForObject(
          "select count(*) from project where id = ? and tenant_id = ? and deleted_at is null",
          Integer.class, projectId, tenantId);
      if (exists == null || exists == 0) {
        throw new BusinessException(ErrorCode.PARAM_ERROR, "项目不存在：" + projectId);
      }
      projectIds.add(projectId);
    }
    jdbcTemplate.update("delete from user_project_scope where tenant_id = ? and user_id = ?", tenantId, userId);
    for (Long projectId : projectIds) {
      jdbcTemplate.update("insert into user_project_scope (tenant_id, user_id, project_id) values (?, ?, ?)",
          tenantId, userId, projectId);
    }
    auditService.record("UPDATE_PROJECT_SCOPE", "user_account", String.valueOf(userId),
        "project_ids=" + projectIds);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("user_id", userId);
    result.put("project_ids", projectIds);
    return result;
  }

  private Map<String, Object> findUser(Long tenantId, Long userId) {
    List<Map<String, Object>> users = jdbcTemplate.queryForList(
        "select id, login_name, display_name, status from user_account where id = ? and tenant_id = ? and deleted_at is null",
        userId, tenantId);
    if (users.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
    return users.get(0);
  }

  private List<String> userRoles(Long tenantId, Long userId) {
    return jdbcTemplate.queryForList(
        "select r.role_code from user_role ur join role r on r.id = ur.role_id where ur.tenant_id = ? and ur.user_id = ?",
        String.class, tenantId, userId);
  }

  private void assignRoles(Long tenantId, Long userId, List<String> roleCodes) {
    jdbcTemplate.update("delete from user_role where tenant_id = ? and user_id = ?", tenantId, userId);
    for (String code : roleCodes) {
      Long roleId = jdbcTemplate.queryForObject(
          "select id from role where tenant_id = ? and role_code = ? and status = 'active' and deleted_at is null",
          Long.class, tenantId, code);
      if (roleId == null) throw new BusinessException(ErrorCode.PARAM_ERROR, "角色不存在：" + code);
      jdbcTemplate.update("insert into user_role (tenant_id, user_id, role_id) values (?, ?, ?)",
          tenantId, userId, roleId);
    }
  }

  @SuppressWarnings("unchecked")
  private List<String> roleCodes(Object value) {
    if (!(value instanceof List)) return new ArrayList<>();
    List<String> codes = new ArrayList<>();
    for (Object item : (List<Object>) value) codes.add(String.valueOf(item));
    return codes;
  }

  private String requiredText(Map<String, Object> request, String field) {
    String value = text(request.get(field));
    if (value.isEmpty()) throw new BusinessException(ErrorCode.PARAM_ERROR, field + " 不能为空");
    return value;
  }

  private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

  private Long generatedId(KeyHolder holder) {
    if (holder.getKeys() != null && holder.getKeys().get("id") != null) {
      return ((Number) holder.getKeys().get("id")).longValue();
    }
    return holder.getKey().longValue();
  }

  private Object[] append(Object[] values, Object... extra) {
    Object[] result = new Object[values.length + extra.length];
    System.arraycopy(values, 0, result, 0, values.length);
    System.arraycopy(extra, 0, result, values.length, extra.length);
    return result;
  }

  public Long currentTenantId() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal.getTenantId();
  }

  public Long currentUserId() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    return principal.getUserId();
  }
}
