package com.bankforecast.importjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** P2-1 真实文件回放：六家银行仿真导出文件，覆盖列名变体、跨页表头、空行、日期格式、多币种和异常编码。 */
class BankFileReplayTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void replaysBocColumnVariants() throws Exception {
    ExcelBankStatementParser.ExcelParseResult result = parseXlsx("replay-boc-column-variants.xlsx");

    assertEquals(1, result.getTemplates().size());
    assertEquals("recognized", result.getTemplates().get(0).get("status"));
    assertEquals("中国银行", result.getTemplates().get(0).get("bank_name"));
    assertEquals(3, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    assertEquals("expense", result.getRows().get(0).getDirection());
    assertEquals("50000.00", result.getRows().get(0).getAmount().toPlainString());
    assertEquals("income", result.getRows().get(1).getDirection());
    assertEquals("120000.00", result.getRows().get(1).getAmount().toPlainString());
  }

  @Test
  void replaysIcbcDateFormats() throws Exception {
    ExcelBankStatementParser.ExcelParseResult result = parseXlsx("replay-icbc-date-formats.xlsx");

    assertEquals(3, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    assertEquals("2026-09-05", result.getRows().get(0).getTransactionDate().toString());
    assertEquals("2026-09-06", result.getRows().get(1).getTransactionDate().toString());
    assertEquals("2026-09-07", result.getRows().get(2).getTransactionDate().toString());
    assertEquals("供应商丁", result.getRows().get(0).getCounterpartyName());
    assertEquals("采购付款", result.getRows().get(0).getSummary());
  }

  @Test
  void replaysCgbMultiCurrency() throws Exception {
    ExcelBankStatementParser.ExcelParseResult result = parseXlsx("replay-cgb-multi-currency.xlsx");

    assertEquals(3, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    assertTrue(result.getRows().get(0).getRawJson().contains("\"currency\":\"USD\""));
    assertTrue(result.getRows().get(1).getRawJson().contains("\"currency\":\"CNY\""));
    assertTrue(result.getRows().get(2).getRawJson().contains("\"currency\":\"HKD\""));
    assertEquals("expense", result.getRows().get(0).getDirection());
    assertEquals("income", result.getRows().get(2).getDirection());
  }

  @Test
  void replaysPabPagedHeaderAndFooter() throws Exception {
    ExcelBankStatementParser.ExcelParseResult result = parseXlsx("replay-pab-paged-header.xlsx");

    assertEquals("recognized", result.getTemplates().get(0).get("status"));
    assertEquals("平安银行", result.getTemplates().get(0).get("bank_name"));
    assertEquals(4, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    assertEquals("PAB-RP-001", result.getRows().get(0).getTransactionNo());
    assertEquals("PAB-RP-004", result.getRows().get(3).getTransactionNo());
    assertEquals("550000.00", result.getRows().get(3).getBalanceAfter().toPlainString());
  }

  @Test
  void replaysBoscDateFormats() throws Exception {
    ExcelBankStatementParser.ExcelParseResult result = parseXlsx("replay-bosc-date-formats.xlsx");

    assertEquals(2, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    assertEquals("2026-09-08", result.getRows().get(0).getTransactionDate().toString());
    assertEquals("2026-09-09", result.getRows().get(1).getTransactionDate().toString());
  }

  @Test
  void replaysSuzbStandardWithBlankRows() throws Exception {
    ExcelBankStatementParser.ExcelParseResult result = parseXlsx("replay-suzb-standard.xlsx");

    assertEquals(2, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    assertEquals("SUZB-RP-002", result.getRows().get(1).getTransactionNo());
  }

  @Test
  void replaysBocGb18030Csv() throws Exception {
    CsvParseResult<CsvBankStatementRow> result = parseCsv("replay-boc-gb18030.csv");

    assertEquals(3, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    assertEquals("expense", result.getRows().get(0).getDirection());
    assertEquals("income", result.getRows().get(1).getDirection());
    assertEquals("2026-09-01", result.getRows().get(0).getTransactionDate().toString());
    assertEquals("2026-09-02", result.getRows().get(1).getTransactionDate().toString());
    assertEquals("2500.50", result.getRows().get(1).getAmount().toPlainString());
    assertTrue(result.getRows().get(1).getRawJson().contains("\"currency\":\"USD\""));
  }

  @Test
  void replaysIcbcUtf16LeTabCsv() throws Exception {
    CsvParseResult<CsvBankStatementRow> result = parseCsv("replay-icbc-utf16le.csv");

    assertEquals(2, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
    assertEquals("2026-09-05", result.getRows().get(0).getTransactionDate().toString());
    assertEquals("2026-09-06", result.getRows().get(1).getTransactionDate().toString());
    assertEquals("客户午", result.getRows().get(0).getCounterpartyName());
    assertEquals("采购", result.getRows().get(1).getSummary());
    assertTrue(result.getRows().get(1).getRawJson().contains("\"currency\":\"HKD\""));
  }

  private ExcelBankStatementParser.ExcelParseResult parseXlsx(String name) {
    return new ExcelBankStatementParser(objectMapper).parse(resource(name), 1000);
  }

  private CsvParseResult<CsvBankStatementRow> parseCsv(String name) {
    return new CsvBankStatementParser(objectMapper).parse(resource(name), 1000);
  }

  private InputStream resource(String name) {
    InputStream in = getClass().getResourceAsStream("/replay/" + name);
    if (in == null) throw new IllegalStateException("缺少回放样本 " + name);
    return in;
  }
}
