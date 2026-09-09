package com.bankforecast.api.dto;

import javax.validation.constraints.Size;

public class RejectMatchResultRequest {
  @Size(max = 256, message = "拒绝原因不能超过256个字符")
  private String reason;

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
