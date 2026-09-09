package com.bankforecast.matching;

import java.util.Locale;

public final class CustomerNameNormalizer {
  private CustomerNameNormalizer() {}

  public static String normalize(String value) {
    if (value == null) return "";
    String normalized = value.toLowerCase(Locale.ROOT)
      .replaceAll("[\\s　·•,，.。()（）【】\\[\\]{}]", "")
      .replace("有限责任公司", "")
      .replace("有限公司", "")
      .replace("公司", "")
      .trim();
    return normalized;
  }
}
