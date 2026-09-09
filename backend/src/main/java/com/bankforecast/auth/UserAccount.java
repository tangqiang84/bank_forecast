package com.bankforecast.auth;

public class UserAccount {

  private final Long id;
  private final Long tenantId;
  private final String loginName;
  private final String displayName;
  private final String passwordHash;
  private final String status;

  public UserAccount(Long id, Long tenantId, String loginName, String displayName,
      String passwordHash, String status) {
    this.id = id;
    this.tenantId = tenantId;
    this.loginName = loginName;
    this.displayName = displayName;
    this.passwordHash = passwordHash;
    this.status = status;
  }

  public Long getId() {
    return id;
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

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getStatus() {
    return status;
  }
}
