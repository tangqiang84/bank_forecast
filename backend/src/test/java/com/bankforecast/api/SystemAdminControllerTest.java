package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "bank-forecast.bootstrap.default-admin-password=test-password-123")
@AutoConfigureMockMvc
class SystemAdminControllerTest {

  private static final String TEST_PASSWORD = "test-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void adminCreatesUserAssignsRolesAndResetsPassword() throws Exception {
    String admin = loginToken("admin01");
    Long tenantId = tenantId("admin01");
    String loginName = "tester" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    mockMvc.perform(post("/api/v1/system/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"" + loginName + "\",\"display_name\":\"测试专员\",\"password\":\"Passw0rd!23\",\"role_codes\":[\"CASHIER\"]}")
            .header("Authorization", "Bearer " + admin).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.login_name").value(loginName))
        .andExpect(jsonPath("$.data.status").value("active"));

    loginTokenWithPassword(loginName, "Passw0rd!23");

    Long userId = jdbcTemplate.queryForObject(
        "select id from user_account where tenant_id = ? and login_name = ?", Long.class, tenantId, loginName);
    mockMvc.perform(put("/api/v1/system/users/" + userId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"display_name\":\"测试专员乙\",\"role_codes\":[\"CASHIER\",\"BUSINESS\"]}")
            .header("Authorization", "Bearer " + admin).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.display_name").value("测试专员乙"));
    Assertions.assertEquals(2, jdbcTemplate.queryForObject(
        "select count(*) from user_role where tenant_id = ? and user_id = ?", Integer.class, tenantId, userId).intValue());

    mockMvc.perform(post("/api/v1/system/users/" + userId + "/reset-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"NewPass456!\"}")
            .header("Authorization", "Bearer " + admin).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk());
    loginTokenWithPassword(loginName, "NewPass456!");
  }

  @Test
  void cfoCannotAccessSystemAdminApis() throws Exception {
    String cfo = loginToken("finance01");
    Long tenantId = tenantId("finance01");
    mockMvc.perform(get("/api/v1/system/users")
            .header("Authorization", "Bearer " + cfo).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void rolePermissionEditAndAdminProtection() throws Exception {
    String admin = loginToken("admin01");
    Long tenantId = tenantId("admin01");
    Long ceoRoleId = jdbcTemplate.queryForObject(
        "select id from role where tenant_id = ? and role_code = 'CEO'", Long.class, tenantId);
    Long adminRoleId = jdbcTemplate.queryForObject(
        "select id from role where tenant_id = ? and role_code = 'ADMIN'", Long.class, tenantId);

    mockMvc.perform(put("/api/v1/system/roles/" + adminRoleId + "/permissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"permission_codes\":[\"dashboard:view\"]}")
            .header("Authorization", "Bearer " + admin).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isBadRequest());

    try {
      mockMvc.perform(put("/api/v1/system/roles/" + ceoRoleId + "/permissions")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"permission_codes\":[\"dashboard:view\",\"audit:view\"]}")
              .header("Authorization", "Bearer " + admin).header("X-Tenant-Id", String.valueOf(tenantId)))
          .andExpect(status().isOk());
      Assertions.assertEquals(2, jdbcTemplate.queryForObject(
          "select count(*) from role_permission where tenant_id = ? and role_id = ?",
          Integer.class, tenantId, ceoRoleId).intValue());
    } finally {
      // 恢复 CEO 角色默认权限，避免影响其他用例
      jdbcTemplate.update("delete from role_permission where tenant_id = ? and role_id = ?", tenantId, ceoRoleId);
      for (String code : new String[] {"dashboard:view", "account:view", "transaction:view", "contract:view",
          "project:view", "matching:view", "exception:view", "attachment:view", "reconciliation:view",
          "report:view", "report:download", "forecast:view"}) {
        jdbcTemplate.update("insert into role_permission (tenant_id, role_id, permission_id) "
                + "select ?, ?, id from permission where permission_code = ?", tenantId, ceoRoleId, code);
      }
    }
  }

  @Test
  void adminCannotDisableSelfAndScopeAssignmentWorks() throws Exception {
    String admin = loginToken("admin01");
    Long tenantId = tenantId("admin01");
    Long adminUserId = jdbcTemplate.queryForObject(
        "select id from user_account where tenant_id = ? and login_name = 'admin01'", Long.class, tenantId);
    Long bizUserId = jdbcTemplate.queryForObject(
        "select id from user_account where tenant_id = ? and login_name = 'biz01'", Long.class, tenantId);

    mockMvc.perform(put("/api/v1/system/users/" + adminUserId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"disabled\"}")
            .header("Authorization", "Bearer " + admin).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isBadRequest());

    Long projectId = jdbcTemplate.queryForObject(
        "select min(id) from project where tenant_id = ? and deleted_at is null", Long.class, tenantId);
    if (projectId == null) {
      jdbcTemplate.update("insert into project (tenant_id, project_no, project_name, customer_name, project_status) values (?, 'PRJ-SCOPE-TEST', '范围测试项目', '客户', 'active')", tenantId);
      projectId = jdbcTemplate.queryForObject(
          "select id from project where tenant_id = ? and project_no = 'PRJ-SCOPE-TEST'", Long.class, tenantId);
    }

    mockMvc.perform(put("/api/v1/system/users/" + bizUserId + "/project-scope")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"project_ids\":[" + projectId + "]}")
            .header("Authorization", "Bearer " + admin).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.project_ids.length()").value(1));

    mockMvc.perform(get("/api/v1/system/users/" + bizUserId + "/project-scope")
            .header("Authorization", "Bearer " + admin).header("X-Tenant-Id", String.valueOf(tenantId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0]").value(projectId.intValue()));
  }

  private Long tenantId(String loginName) {
    return jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = ?", Long.class, loginName);
  }

  private String loginToken(String loginName) throws Exception {
    return loginTokenWithPassword(loginName, TEST_PASSWORD);
  }

  private String loginTokenWithPassword(String loginName, String password) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"" + loginName + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
