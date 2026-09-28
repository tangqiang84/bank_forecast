package com.bankforecast.api.dto;

import java.util.List;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;

public class TransactionBatchUnlinkRequest {
  @NotEmpty @Size(max = 500) private List<Long> transaction_ids;
  @NotBlank @Size(max = 512) private String reason;
  public List<Long> getTransaction_ids() { return transaction_ids; }
  public void setTransaction_ids(List<Long> transactionIds) { this.transaction_ids = transactionIds; }
  public String getReason() { return reason; }
  public void setReason(String reason) { this.reason = reason; }
}
