package com.bankforecast.importjob;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CsvBankStatementRow {

  private final int rowNo;
  private final String transactionNo;
  private final LocalDate transactionDate;
  private final String direction;
  private final BigDecimal amount;
  private final BigDecimal balanceAfter;
  private final String counterpartyName;
  private final String summary;
  private final String rawJson;

  public CsvBankStatementRow(int rowNo, String transactionNo, LocalDate transactionDate,
      String direction, BigDecimal amount, BigDecimal balanceAfter, String counterpartyName,
      String summary, String rawJson) {
    this.rowNo = rowNo;
    this.transactionNo = transactionNo;
    this.transactionDate = transactionDate;
    this.direction = direction;
    this.amount = amount;
    this.balanceAfter = balanceAfter;
    this.counterpartyName = counterpartyName;
    this.summary = summary;
    this.rawJson = rawJson;
  }

  public int getRowNo() { return rowNo; }
  public String getTransactionNo() { return transactionNo; }
  public LocalDate getTransactionDate() { return transactionDate; }
  public String getDirection() { return direction; }
  public BigDecimal getAmount() { return amount; }
  public BigDecimal getBalanceAfter() { return balanceAfter; }
  public String getCounterpartyName() { return counterpartyName; }
  public String getSummary() { return summary; }
  public String getRawJson() { return rawJson; }
}
