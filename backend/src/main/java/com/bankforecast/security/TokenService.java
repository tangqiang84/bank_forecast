package com.bankforecast.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TokenService {

  private static final String ALGORITHM = "HmacSHA256";

  private final ObjectMapper objectMapper;
  private final String secret;
  private final long ttlSeconds;

  public TokenService(ObjectMapper objectMapper,
      @Value("${bank-forecast.security.jwt-secret}") String secret,
      @Value("${bank-forecast.security.access-token-ttl-minutes}") long ttlMinutes) {
    this.objectMapper = objectMapper;
    this.secret = secret;
    this.ttlSeconds = ttlMinutes * 60L;
  }

  @PostConstruct
  public void validateSecret() {
    if (!StringUtils.hasText(secret)) {
      throw new IllegalStateException("必须配置 JWT_SECRET 后才能启动认证服务");
    }
  }

  public String issue(Long userId, Long tenantId, String loginName) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("user_id", userId);
      payload.put("tenant_id", tenantId);
      payload.put("login_name", loginName);
      payload.put("exp", Instant.now().getEpochSecond() + ttlSeconds);

      String body = Base64.getUrlEncoder().withoutPadding()
          .encodeToString(objectMapper.writeValueAsBytes(payload));
      return body + "." + sign(body);
    } catch (Exception ex) {
      throw new IllegalStateException("令牌生成失败", ex);
    }
  }

  public Map<String, Object> verify(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length != 2 || !sign(parts[0]).equals(parts[1])) {
        return null;
      }
      byte[] decoded = Base64.getUrlDecoder().decode(parts[0]);
      Map<String, Object> payload = objectMapper.readValue(decoded, new TypeReference<Map<String, Object>>() {});
      Number expiresAt = (Number) payload.get("exp");
      if (expiresAt == null || expiresAt.longValue() < Instant.now().getEpochSecond()) {
        return null;
      }
      return payload;
    } catch (Exception ex) {
      return null;
    }
  }

  private String sign(String body) throws Exception {
    Mac mac = Mac.getInstance(ALGORITHM);
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }
}
