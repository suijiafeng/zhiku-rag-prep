package dev.zhiku.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.zhiku.auth.Role;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

/** 用每次 JVM 运行时生成的密钥签发测试 token；仓库中不保存任何内容。 */
public final class TestJwt {
  public static final String SECRET = randomSecret();
  public static final String ISSUER = "zhiku-local";
  public static final String AUDIENCE = "zhiku-api";

  private TestJwt() {}

  private static String randomSecret() {
    byte[] bytes = new byte[48];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  public static String token(UUID enterprise, UUID user, Role... roles) {
    return token(enterprise, user, b -> {}, SECRET, roles);
  }

  public static String token(UUID enterprise, UUID user, Consumer<JWTClaimsSet.Builder> customize, String secret, Role... roles) {
    var now = Instant.now();
    var claims = new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject(user.toString())
        .claim("enterprise_id", enterprise.toString())
        .claim("roles", Arrays.stream(roles).map(Enum::name).toList())
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)));
    customize.accept(claims);
    try {
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
      jwt.sign(new MACSigner(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String bearer(String token) {
    return "Bearer " + token;
  }
}
