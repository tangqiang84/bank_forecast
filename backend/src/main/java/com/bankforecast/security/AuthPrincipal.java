package com.bankforecast.security;

import java.util.List;

public class AuthPrincipal {

  private final Long userId;
  private final Long tenantId;
  private final String loginName;
  private final String displayName;
  private final List<String> roles;

  public AuthPrincipal(Long userId, Long tenantId, String loginName, String displayName, List<String> roles) {
    this.userId = userId;
    this.tenantId = tenantId;
    this.loginName = loginName;
    this.displayName = displayName;
    this.roles = roles;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public String getLoginName() {
    return loginName;
  }

  public String getDisplayName() {
    return displayName;
  }

  public List<String> getRoles() {
    return roles;
  }
}
