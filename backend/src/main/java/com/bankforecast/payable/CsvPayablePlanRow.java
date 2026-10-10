package com.bankforecast.payable;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CsvPayablePlanRow {
  private final int rowNo;
  private final String contractNo;
  private final String supplierName;
  private final String nodeName;
  private final LocalDate dueDate;
  private final BigDecimal planAmount;
  private final BigDecimal paidAmount;
  private final String status;
  private final String remark;
  private final String rawJson;

  public CsvPayablePlanRow(int rowNo, String contractNo, String supplierName, String nodeName,
      LocalDate dueDate, BigDecimal planAmount, BigDecimal paidAmount, String status,
      String remark, String rawJson) {
    this.rowNo = rowNo;
    this.contractNo = contractNo;
    this.supplierName = supplierName;
    this.nodeName = nodeName;
    this.dueDate = dueDate;
    this.planAmount = planAmount;
    this.paidAmount = paidAmount;
    this.status = status;
    this.remark = remark;
    this.rawJson = rawJson;
  }

  public int getRowNo() { return rowNo; }
  public String getContractNo() { return contractNo; }
  public String getSupplierName() { return supplierName; }
  public String getNodeName() { return nodeName; }
  public LocalDate getDueDate() { return dueDate; }
  public BigDecimal getPlanAmount() { return planAmount; }
  public BigDecimal getPaidAmount() { return paidAmount; }
  public String getStatus() { return status; }
  public String getRemark() { return remark; }
  public String getRawJson() { return rawJson; }
}
