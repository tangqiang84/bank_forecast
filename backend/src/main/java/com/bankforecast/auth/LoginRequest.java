package com.bankforecast.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotBlank;

public class LoginRequest {

  @NotBlank(message = "登录名不能为空")
  @JsonProperty("login_name")
  private String loginName;

  @NotBlank(message = "密码不能为空")
  private String password;

  public String getLoginName() {
    return loginName;
  }

  public void setLoginName(String loginName) {
    this.loginName = loginName;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
