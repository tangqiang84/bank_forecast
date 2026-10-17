package com.bankforecast.importjob;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 弱指纹去重（R-NRM-006）：账户 + 日期 + 方向 + 金额 + 归一化对手方 + 归一化摘要。
 * 账户维度由查询条件 bank_account_id 承担，指纹串本身不含账户。
 * 确定性 BOSC1/SUZB1/EAB1/CMB1 标准号使用强身份作为指纹，避免不同标准号被旧弱字段误杀。
 */
public final class WeakFingerprint {
  private WeakFingerprint() {}

  public static String of(CsvBankStatementRow row) {
    String transactionNo = row.getTransactionNo();
    if (transactionNo != null && transactionNo.matches("(BOSC1|SUZB1|EAB1|CMB1)-[0-9a-f]{64}")) {
      return transactionNo.substring(0, transactionNo.indexOf('-')) + "|" + transactionNo;
    }
    return of(row.getTransactionDate().toString(), row.getDirection(), row.getAmount(),
        row.getCounterpartyName(), row.getSummary());
  }

  public static String of(String dateIso, String direction, BigDecimal amount,
      String counterpartyName, String summary) {
    return String.join("|",
        dateIso,
        direction == null ? "" : direction.trim().toLowerCase(),
        amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
        normalize(counterpartyName),
        normalize(summary));
  }

  private static String normalize(String value) {
    if (value == null) return "";
    return value.trim().toLowerCase().replaceAll("\\s+", " ");
  }
}
