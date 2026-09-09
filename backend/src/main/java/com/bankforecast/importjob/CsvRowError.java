package com.bankforecast.importjob;

public class CsvRowError {
  private final int rowNo;
  private final String field;
  private final String message;
  private final String rawJson;

  public CsvRowError(int rowNo, String field, String message, String rawJson) {
    this.rowNo = rowNo;
    this.field = field;
    this.message = message;
    this.rawJson = rawJson;
  }

  public int getRowNo() { return rowNo; }
  public String getField() { return field; }
  public String getMessage() { return message; }
  public String getRawJson() { return rawJson; }
}
