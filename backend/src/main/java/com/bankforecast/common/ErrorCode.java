package com.bankforecast.common;

public final class ErrorCode {

  public static final int PARAM_ERROR = 40001;
  public static final int FILE_TYPE_UNSUPPORTED = 44005;
  public static final int FILE_EMPTY = 44010;
  public static final int ROW_DATA_ERROR = 44015;
  public static final int LOGIN_REQUIRED = 41001;
  public static final int PASSWORD_ERROR = 41006;
  public static final int ACCOUNT_NOT_FOUND = 41007;
  public static final int TENANT_INVALID = 41003;
  public static final int RESOURCE_NOT_FOUND = 42001;
  public static final int DUPLICATE_DATA = 43001;
  public static final int SYSTEM_ERROR = 50001;

  private ErrorCode() {}
}
