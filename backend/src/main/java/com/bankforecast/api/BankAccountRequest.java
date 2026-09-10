package com.bankforecast.api;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class BankAccountRequest {
  @NotBlank @Size(max = 64) private String bankCode;
  @NotBlank @Size(max = 128) private String bankName;
  @NotBlank @Size(max = 128) private String accountName;
  @NotBlank @Size(min = 4, max = 64) private String accountNo;
  @NotBlank @Size(max = 16) private String currency;
  @DecimalMin(value = "0.00") private BigDecimal currentBalance = BigDecimal.ZERO;

  public String getBankCode() { return bankCode; }
  public void setBankCode(String bankCode) { this.bankCode = bankCode; }
  public String getBankName() { return bankName; }
  public void setBankName(String bankName) { this.bankName = bankName; }
  public String getAccountName() { return accountName; }
  public void setAccountName(String accountName) { this.accountName = accountName; }
  public String getAccountNo() { return accountNo; }
  public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }
  public BigDecimal getCurrentBalance() { return currentBalance == null ? BigDecimal.ZERO : currentBalance; }
  public void setCurrentBalance(BigDecimal currentBalance) { this.currentBalance = currentBalance; }
}
