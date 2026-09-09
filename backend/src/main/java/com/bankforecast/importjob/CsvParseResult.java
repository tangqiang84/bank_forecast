package com.bankforecast.importjob;

import java.util.List;

public class CsvParseResult<T> {
  private final List<T> rows;
  private final List<CsvRowError> errors;
  private final int totalRows;

  public CsvParseResult(List<T> rows, List<CsvRowError> errors, int totalRows) {
    this.rows = rows;
    this.errors = errors;
    this.totalRows = totalRows;
  }

  public List<T> getRows() { return rows; }
  public List<CsvRowError> getErrors() { return errors; }
  public int getTotalRows() { return totalRows; }
}
