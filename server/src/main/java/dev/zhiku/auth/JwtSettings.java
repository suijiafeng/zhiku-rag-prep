package dev.zhiku.auth;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhiku.auth")
public record JwtSettings(String jwtSecret, String issuer, String audience) {
  static final int MIN_SECRET_BYTES = 32;

  public boolean configured() {
    return jwtSecret != null && jwtSecret.getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
  }
}
