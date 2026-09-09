package com.bankforecast.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class PasswordHashService {

  private final SecureRandom secureRandom = new SecureRandom();

  public String hash(String password) {
    byte[] salt = new byte[16];
    secureRandom.nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt) + ":" + digest(salt, password);
  }

  public boolean matches(String password, String storedHash) {
    if (storedHash == null || !storedHash.contains(":")) {
      return false;
    }
    String[] parts = storedHash.split(":", 2);
    byte[] salt = Base64.getDecoder().decode(parts[0]);
    return MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8), digest(salt, password).getBytes(StandardCharsets.UTF_8));
  }

  private String digest(byte[] salt, String password) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(salt);
      digest.update(password.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(digest.digest());
    } catch (Exception ex) {
      throw new IllegalStateException("密码哈希失败", ex);
    }
  }
}
