package com.bankforecast.importjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bankforecast.contract.CsvContractParser;
import com.bankforecast.contract.CsvContractRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvParserTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void parsesBankStatementWithInOutAndBom() {
    CsvBankStatementParser parser = new CsvBankStatementParser(objectMapper);
    String csv = "\uFEFF交易流水号,交易日期,收支方向,交易金额\nTX-1,2026/09/09,in,\"1,234.50\"\n";

    CsvParseResult<CsvBankStatementRow> result = parser.parse(
        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), 10);

    assertEquals(1, result.getRows().size());
    assertEquals("income", result.getRows().get(0).getDirection());
    assertEquals("1234.50", result.getRows().get(0).getAmount().toPlainString());
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  void parsesContractWithChineseHeadersAndGb18030() {
    CsvContractParser parser = new CsvContractParser(objectMapper);
    String csv = "合同编号,合同名称,客户名称,合同金额,节点名称,节点类型,到期日期,应收金额\n"
        + "HT-1,软件合同,甲方公司,\"10,000.00\",验收款,acceptance,20260909,\"10,000.00\"\n";

    CsvParseResult<CsvContractRow> result = parser.parse(
        new ByteArrayInputStream(csv.getBytes(Charset.forName("GB18030"))), 10);

    assertEquals(1, result.getRows().size());
    assertEquals("软件合同", result.getRows().get(0).getContractName());
    assertEquals("2026-09-09", result.getRows().get(0).getDueDate().toString());
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  void acceptsRequiredMarkerInContractHeader() {
    CsvContractParser parser = new CsvContractParser(objectMapper);
    String csv = "合同编号,合同名称（必填）,客户名称,合同金额,节点名称,节点类型,到期日期,应收金额\n"
        + "HT-2,软件合同,甲方公司,100.00,验收款,acceptance,2026-09-09,100.00\n";

    CsvParseResult<CsvContractRow> result = parser.parse(
        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), 10);

    assertEquals(1, result.getRows().size());
    assertTrue(result.getErrors().isEmpty());
  }
}
