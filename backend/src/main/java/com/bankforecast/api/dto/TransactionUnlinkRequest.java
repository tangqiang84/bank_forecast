package com.bankforecast.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TransactionUnlinkRequest {
  @NotBlank @Size(max = 512) private String reason;
  public String getReason() { return reason; }
  public void setReason(String reason) { this.reason = reason; }
}
