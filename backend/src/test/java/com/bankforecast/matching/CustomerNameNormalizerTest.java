package com.bankforecast.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CustomerNameNormalizerTest {
  @Test
  void removesCompanySuffixWhitespaceAndPunctuation() {
    assertEquals("甲方科技", CustomerNameNormalizer.normalize(" 甲方科技有限公司 "));
    assertEquals("甲方科技", CustomerNameNormalizer.normalize("甲方科技·有限责任公司"));
  }
}
