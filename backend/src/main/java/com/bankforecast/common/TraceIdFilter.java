package com.bankforecast.common;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 请求级 trace 上下文：透传入站 X-Trace-Id，缺失时生成，响应头回写，结束后清理。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Trace-Id";
  private static final int MAX_LENGTH = 64;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String traceId = sanitize(request.getHeader(HEADER));
    if (traceId == null) traceId = TraceIdHolder.next();
    TraceIdHolder.set(traceId);
    response.setHeader(HEADER, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      TraceIdHolder.clear();
    }
  }

  private String sanitize(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) return null;
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
          || c == '-' || c == '_';
      if (!valid) return null;
    }
    return trimmed;
  }
}
