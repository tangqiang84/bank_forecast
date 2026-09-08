package com.bankforecast.common;

import java.util.UUID;

public final class TraceIdHolder {

  private TraceIdHolder() {}

  public static String next() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
