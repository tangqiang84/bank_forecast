package com.bankforecast.project;

import java.time.LocalDate;

public class CsvProjectRow {
  private final int rowNo;
  private final String projectNo;
  private final String projectName;
  private final String customerName;
  private final String projectManager;
  private final String projectStatus;
  private final LocalDate startDate;
  private final LocalDate deliveryDate;
  private final LocalDate acceptanceDate;
  private final String remark;
  private final String rawJson;

  public CsvProjectRow(int rowNo, String projectNo, String projectName, String customerName,
      String projectManager, String projectStatus, LocalDate startDate, LocalDate deliveryDate,
      LocalDate acceptanceDate, String remark, String rawJson) {
    this.rowNo = rowNo;
    this.projectNo = projectNo;
    this.projectName = projectName;
    this.customerName = customerName;
    this.projectManager = projectManager;
    this.projectStatus = projectStatus;
    this.startDate = startDate;
    this.deliveryDate = deliveryDate;
    this.acceptanceDate = acceptanceDate;
    this.remark = remark;
    this.rawJson = rawJson;
  }

  public int getRowNo() { return rowNo; }
  public String getProjectNo() { return projectNo; }
  public String getProjectName() { return projectName; }
  public String getCustomerName() { return customerName; }
  public String getProjectManager() { return projectManager; }
  public String getProjectStatus() { return projectStatus; }
  public LocalDate getStartDate() { return startDate; }
  public LocalDate getDeliveryDate() { return deliveryDate; }
  public LocalDate getAcceptanceDate() { return acceptanceDate; }
  public String getRemark() { return remark; }
  public String getRawJson() { return rawJson; }
}
