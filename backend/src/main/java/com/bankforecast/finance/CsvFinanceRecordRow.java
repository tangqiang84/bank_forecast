package com.bankforecast.finance;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CsvFinanceRecordRow {
  private final int rowNo;
  private final String recordNo;
  private final String recordType;
  private final LocalDate recordDate;
  private final LocalDate postingDate;
  private final String counterpartyName;
  private final BigDecimal amount;
  private final String summary;
  private final String sourceSystem;
  private final String contractNo;
  private final String projectNo;
  private final String remark;
  private final String rawJson;

  public CsvFinanceRecordRow(int rowNo, String recordNo, String recordType, LocalDate recordDate,
      LocalDate postingDate, String counterpartyName, BigDecimal amount, String summary,
      String sourceSystem, String contractNo, String projectNo, String remark, String rawJson) {
    this.rowNo = rowNo;
    this.recordNo = recordNo;
    this.recordType = recordType;
    this.recordDate = recordDate;
    this.postingDate = postingDate;
    this.counterpartyName = counterpartyName;
    this.amount = amount;
    this.summary = summary;
    this.sourceSystem = sourceSystem;
    this.contractNo = contractNo;
    this.projectNo = projectNo;
    this.remark = remark;
    this.rawJson = rawJson;
  }

  public int getRowNo() { return rowNo; }
  public String getRecordNo() { return recordNo; }
  public String getRecordType() { return recordType; }
  public LocalDate getRecordDate() { return recordDate; }
  public LocalDate getPostingDate() { return postingDate; }
  public String getCounterpartyName() { return counterpartyName; }
  public BigDecimal getAmount() { return amount; }
  public String getSummary() { return summary; }
  public String getSourceSystem() { return sourceSystem; }
  public String getContractNo() { return contractNo; }
  public String getProjectNo() { return projectNo; }
  public String getRemark() { return remark; }
  public String getRawJson() { return rawJson; }
}
