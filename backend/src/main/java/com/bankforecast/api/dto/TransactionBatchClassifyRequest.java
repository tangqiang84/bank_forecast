package com.bankforecast.api.dto;

import java.util.List;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;

public class TransactionBatchClassifyRequest {
  @NotEmpty @Size(max = 500) private List<Long> transaction_ids;
  @NotBlank @Size(max = 64) private String category;
  @Size(max = 256) private String purpose;
  @Size(max = 512) private String remark;
  public List<Long> getTransaction_ids() { return transaction_ids; }
  public void setTransaction_ids(List<Long> transactionIds) { this.transaction_ids = transactionIds; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public String getPurpose() { return purpose; }
  public void setPurpose(String purpose) { this.purpose = purpose; }
  public String getRemark() { return remark; }
  public void setRemark(String remark) { this.remark = remark; }
}
