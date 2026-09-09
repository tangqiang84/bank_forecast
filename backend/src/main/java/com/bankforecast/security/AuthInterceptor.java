package com.bankforecast.security;

import com.bankforecast.auth.UserAccount;
import com.bankforecast.auth.UserAccountRepository;
import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

  private final TokenService tokenService;
  private final UserAccountRepository userAccountRepository;

  public AuthInterceptor(TokenService tokenService, UserAccountRepository userAccountRepository) {
    this.tokenService = tokenService;
    this.userAccountRepository = userAccountRepository;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublicPath(request.getRequestURI())) {
      return true;
    }

    String authorization = request.getHeader("Authorization");
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    }

    Map<String, Object> payload = tokenService.verify(authorization.substring("Bearer ".length()));
    if (payload == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    }

    Long tenantId = ((Number) payload.get("tenant_id")).longValue();
    Long userId = ((Number) payload.get("user_id")).longValue();
    String headerTenantId = request.getHeader("X-Tenant-Id");
    if (headerTenantId != null && !headerTenantId.trim().isEmpty()
        && !String.valueOf(tenantId).equals(headerTenantId.trim())) {
      throw new BusinessException(ErrorCode.TENANT_INVALID, "租户信息异常");
    }

    UserAccount user = userAccountRepository.findById(userId);
    if (user == null || !"active".equals(user.getStatus())) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    }

    AuthContext.set(new AuthPrincipal(
        user.getId(),
        user.getTenantId(),
        user.getLoginName(),
        user.getDisplayName(),
        userAccountRepository.findRoleCodes(user.getTenantId(), user.getId())));
    return true;
  }

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
      Object handler, Exception ex) {
    AuthContext.clear();
  }

  private boolean isPublicPath(String path) {
    return path.equals("/api/v1/health")
        || path.equals("/api/v1/auth/login")
        || path.startsWith("/actuator")
        || path.startsWith("/h2-console");
  }
}
