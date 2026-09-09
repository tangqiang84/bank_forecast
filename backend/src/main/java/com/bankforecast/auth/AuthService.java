package com.bankforecast.auth;

import com.bankforecast.common.BusinessException;
import com.bankforecast.common.ErrorCode;
import com.bankforecast.security.AuthContext;
import com.bankforecast.security.AuthPrincipal;
import com.bankforecast.security.PasswordHashService;
import com.bankforecast.security.TokenService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  private final UserAccountRepository userAccountRepository;
  private final PasswordHashService passwordHashService;
  private final TokenService tokenService;

  public AuthService(UserAccountRepository userAccountRepository,
      PasswordHashService passwordHashService, TokenService tokenService) {
    this.userAccountRepository = userAccountRepository;
    this.passwordHashService = passwordHashService;
    this.tokenService = tokenService;
  }

  public Map<String, Object> login(LoginRequest request) {
    UserAccount user = userAccountRepository.findByLoginName(request.getLoginName());
    if (user == null) {
      throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "账号不存在");
    }
    if (!"active".equals(user.getStatus())) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "账号已停用，请联系管理员");
    }
    if (!passwordHashService.matches(request.getPassword(), user.getPasswordHash())) {
      throw new BusinessException(ErrorCode.PASSWORD_ERROR, "密码错误");
    }

    userAccountRepository.updateLastLoginAt(user.getId());
    List<String> roles = userAccountRepository.findRoleCodes(user.getTenantId(), user.getId());

    Map<String, Object> userData = userData(user, roles);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("access_token", tokenService.issue(user.getId(), user.getTenantId(), user.getLoginName()));
    data.put("token_type", "Bearer");
    data.put("user", userData);
    return data;
  }

  public Map<String, Object> currentUser() {
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED, "登录已过期，请重新登录");
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", principal.getUserId());
    data.put("tenant_id", principal.getTenantId());
    data.put("login_name", principal.getLoginName());
    data.put("display_name", principal.getDisplayName());
    data.put("roles", principal.getRoles());
    data.put("permissions", permissions(principal.getRoles()));
    return data;
  }

  private Map<String, Object> userData(UserAccount user, List<String> roles) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", user.getId());
    data.put("tenant_id", user.getTenantId());
    data.put("login_name", user.getLoginName());
    data.put("display_name", user.getDisplayName());
    data.put("status", user.getStatus());
    data.put("roles", roles);
    data.put("permissions", permissions(roles));
    return data;
  }

  private List<String> permissions(List<String> roles) {
    if (roles.contains("ADMIN") || roles.contains("CFO")) {
      return java.util.Arrays.asList("DASHBOARD_VIEW", "IMPORT_MANAGE", "BANK_VIEW", "EXCEPTION_MANAGE");
    }
    return java.util.Arrays.asList("DASHBOARD_VIEW");
  }
}
