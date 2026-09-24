package com.bankforecast.security;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PermissionInterceptor implements HandlerInterceptor {

  private final PermissionRepository permissionRepository;

  public PermissionInterceptor(PermissionRepository permissionRepository) {
    this.permissionRepository = permissionRepository;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod)) {
      return true;
    }
    RequirePermission required = ((HandlerMethod) handler).getMethodAnnotation(RequirePermission.class);
    if (required == null) {
      return true;
    }
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    }
    Set<String> permissions = permissionRepository.findPermissionCodes(
        principal.getTenantId(), principal.getRoles());
    if (!permissions.contains(required.value())) {
      throw new BusinessException(ErrorCode.PERMISSION_DENIED, "没有执行该操作的权限");
    }
    return true;
  }
}
