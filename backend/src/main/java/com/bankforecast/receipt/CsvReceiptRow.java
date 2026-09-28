package com.bankforecast.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CsvReceiptRow {
  private final int rowNo;
  private final String bankName;
  private final String receiptNo;
  private final LocalDate printDate;
  private final LocalDate transactionDate;
  private final String transactionTime;
  private final String currency;
  private final String payerName;
  private final String payerAccountLast4;
  private final String payeeName;
  private final String payeeAccountLast4;
  private final String payerBank;
  private final String payeeBank;
  private final BigDecimal amount;
  private final String summary;
  private final String transactionNo;
  private final String channel;
  private final String verificationCode;
  private final String rawJson;

  public CsvReceiptRow(int rowNo, String bankName, String receiptNo, LocalDate printDate,
      LocalDate transactionDate, String transactionTime, String currency, String payerName,
      String payerAccountLast4, String payeeName, String payeeAccountLast4, String payerBank,
      String payeeBank, BigDecimal amount, String summary, String transactionNo, String channel,
      String verificationCode, String rawJson) {
    this.rowNo = rowNo;
    this.bankName = bankName;
    this.receiptNo = receiptNo;
    this.printDate = printDate;
    this.transactionDate = transactionDate;
    this.transactionTime = transactionTime;
    this.currency = currency;
    this.payerName = payerName;
    this.payerAccountLast4 = payerAccountLast4;
    this.payeeName = payeeName;
    this.payeeAccountLast4 = payeeAccountLast4;
    this.payerBank = payerBank;
    this.payeeBank = payeeBank;
    this.amount = amount;
    this.summary = summary;
    this.transactionNo = transactionNo;
    this.channel = channel;
    this.verificationCode = verificationCode;
    this.rawJson = rawJson;
  }

  public int getRowNo() { return rowNo; }
  public String getBankName() { return bankName; }
  public String getReceiptNo() { return receiptNo; }
  public LocalDate getPrintDate() { return printDate; }
  public LocalDate getTransactionDate() { return transactionDate; }
  public String getTransactionTime() { return transactionTime; }
  public String getCurrency() { return currency; }
  public String getPayerName() { return payerName; }
  public String getPayerAccountLast4() { return payerAccountLast4; }
  public String getPayeeName() { return payeeName; }
  public String getPayeeAccountLast4() { return payeeAccountLast4; }
  public String getPayerBank() { return payerBank; }
  public String getPayeeBank() { return payeeBank; }
  public BigDecimal getAmount() { return amount; }
  public String getSummary() { return summary; }
  public String getTransactionNo() { return transactionNo; }
  public String getChannel() { return channel; }
  public String getVerificationCode() { return verificationCode; }
  public String getRawJson() { return rawJson; }
}
