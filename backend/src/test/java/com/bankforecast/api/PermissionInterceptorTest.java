package com.bankforecast.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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
class PermissionInterceptorTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void cashierCannotAccessProjectEndpoints() throws Exception {
    String token = loginToken("cashier01");
    mockMvc.perform(get("/api/v1/projects")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", tenantId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void ceoCannotGenerateReport() throws Exception {
    String token = loginToken("ceo01");
    mockMvc.perform(post("/api/v1/reports")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", tenantId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"report_type\":\"daily\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void businessUserCannotViewDashboard() throws Exception {
    String token = loginToken("biz01");
    mockMvc.perform(get("/api/v1/dashboard/overview")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", tenantId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void cfoCanAccessDashboard() throws Exception {
    String token = loginToken("finance01");
    mockMvc.perform(get("/api/v1/dashboard/overview")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void currentUserReturnsDatabaseDrivenPermissions() throws Exception {
    String cfoToken = loginToken("finance01");
    mockMvc.perform(get("/api/v1/auth/me")
            .header("Authorization", "Bearer " + cfoToken)
            .header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permissions").isArray())
        .andExpect(jsonPath("$.data.permissions[?(@ == 'report:generate')]").exists());

    String cashierToken = loginToken("cashier01");
    mockMvc.perform(get("/api/v1/auth/me")
            .header("Authorization", "Bearer " + cashierToken)
            .header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permissions[?(@ == 'transaction:import')]").exists())
        .andExpect(jsonPath("$.data.permissions[?(@ == 'project:rule')]").isEmpty());
  }

  @Test
  void businessUserProjectListFollowsProjectScope() throws Exception {
    String token = loginToken("biz01");
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String projectNo = "PRJ-SCOPE-" + suffix;
    jdbcTemplate.update(
        "insert into project (tenant_id, project_no, project_name, customer_name, project_status) values (?, ?, ?, ?, 'active')",
        Long.valueOf(tenantId()), projectNo, "范围测试项目", "范围测试客户");
    Long projectId = jdbcTemplate.queryForObject(
        "select id from project where project_no = ?", Long.class, projectNo);

    mockMvc.perform(get("/api/v1/projects?project_no=" + projectNo)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
    mockMvc.perform(get("/api/v1/projects/" + projectId)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", tenantId()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(42001));

    Long userId = jdbcTemplate.queryForObject(
        "select id from user_account where login_name = 'biz01'", Long.class);
    jdbcTemplate.update(
        "insert into user_project_scope (tenant_id, user_id, project_id) values (?, ?, ?)",
        Long.valueOf(tenantId()), userId, projectId);

    mockMvc.perform(get("/api/v1/projects?project_no=" + projectNo)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1));
    mockMvc.perform(get("/api/v1/projects/" + projectId)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", tenantId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.project.project_no").value(projectNo));
  }

  private String tenantId() {
    return String.valueOf(jdbcTemplate.queryForObject(
        "select tenant_id from user_account where login_name = 'finance01'", Long.class));
  }

  private String loginToken(String loginName) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login_name\":\"" + loginName + "\",\"password\":\"test-password-123\"}"))
        .andExpect(status().isOk()).andReturn();
    String body = result.getResponse().getContentAsString();
    String token = body.substring(body.indexOf("access_token") + 15);
    return token.substring(0, token.indexOf('"'));
  }
}
