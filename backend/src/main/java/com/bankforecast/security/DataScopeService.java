package com.bankforecast.security;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DataScopeService {

  private final PermissionRepository permissionRepository;

  public DataScopeService(PermissionRepository permissionRepository) {
    this.permissionRepository = permissionRepository;
  }

  /**
   * 返回业务负责人的项目数据范围；返回 null 表示不限制。
   * 仅对仅有 BUSINESS 角色（无 ADMIN/CFO）的用户生效；无范围记录时返回空列表，即不可见任何项目。
   */
  public List<Long> projectScopeOrNull(AuthPrincipal principal) {
    if (principal == null) return null;
    List<String> roles = principal.getRoles();
    if (roles.contains("ADMIN") || roles.contains("CFO")) return null;
    if (!roles.contains("BUSINESS")) return null;
    return permissionRepository.findScopedProjectIds(principal.getTenantId(), principal.getUserId());
  }
}
