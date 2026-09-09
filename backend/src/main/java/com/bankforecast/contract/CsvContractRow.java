package com.bankforecast.contract;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CsvContractRow {
  private final int rowNo;
  private final String contractNo;
  private final String contractName;
  private final String customerName;
  private final String projectNo;
  private final String projectName;
  private final BigDecimal contractAmount;
  private final String nodeName;
  private final String nodeType;
  private final LocalDate dueDate;
  private final BigDecimal planAmount;
  private final String ownerName;
  private final String rawJson;

  public CsvContractRow(int rowNo, String contractNo, String contractName, String customerName,
      String projectNo, String projectName, BigDecimal contractAmount, String nodeName,
      String nodeType, LocalDate dueDate, BigDecimal planAmount, String ownerName, String rawJson) {
    this.rowNo = rowNo;
    this.contractNo = contractNo;
    this.contractName = contractName;
    this.customerName = customerName;
    this.projectNo = projectNo;
    this.projectName = projectName;
    this.contractAmount = contractAmount;
    this.nodeName = nodeName;
    this.nodeType = nodeType;
    this.dueDate = dueDate;
    this.planAmount = planAmount;
    this.ownerName = ownerName;
    this.rawJson = rawJson;
  }

  public int getRowNo() { return rowNo; }
  public String getContractNo() { return contractNo; }
  public String getContractName() { return contractName; }
  public String getCustomerName() { return customerName; }
  public String getProjectNo() { return projectNo; }
  public String getProjectName() { return projectName; }
  public BigDecimal getContractAmount() { return contractAmount; }
  public String getNodeName() { return nodeName; }
  public String getNodeType() { return nodeType; }
  public LocalDate getDueDate() { return dueDate; }
  public BigDecimal getPlanAmount() { return planAmount; }
  public String getOwnerName() { return ownerName; }
  public String getRawJson() { return rawJson; }
}
