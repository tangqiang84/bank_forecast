package com.bankforecast.importjob;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 六家支持银行的模板字典：字段映射来自解析器实际的表头归一化规则。 */
@Component
public class BankTemplateCatalog {

  private static final String[] BANK_CODES = {"BOC", "ICBC", "CGB", "PAB", "BOSC", "SUZB"};
  private static final String[] SAMPLE_HEADERS = {
      "交易日期", "日期", "交易日", "凭证号", "交易流水号", "流水号",
      "借方金额", "支出金额", "贷方金额", "收入金额", "余额", "交易后余额",
      "对方户名", "对手方", "摘要", "交易用途", "备注"};

  private final ExcelBankStatementParser parser;

  public BankTemplateCatalog(ExcelBankStatementParser parser) {
    this.parser = parser;
  }

  public List<Map<String, Object>> templates() {
    List<Map<String, Object>> mappings = new ArrayList<>();
    for (String header : SAMPLE_HEADERS) {
      Map<String, Object> mapping = new LinkedHashMap<>();
      mapping.put("header", header);
      mapping.put("field", parser.canonicalHeader(header));
      mappings.add(mapping);
    }
    List<Map<String, Object>> result = new ArrayList<>();
    String[] banks = ExcelBankStatementParser.SUPPORTED_BANKS;
    for (int i = 0; i < banks.length; i++) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("bank_code", BANK_CODES[i]);
      item.put("bank_name", banks[i]);
      item.put("format", "xlsx/csv");
      item.put("recognition", "工作表名称包含银行名称 + 表头归一化映射");
      item.put("field_mappings", mappings);
      result.add(item);
    }
    return result;
  }
}
