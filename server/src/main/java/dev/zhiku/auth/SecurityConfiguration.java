package dev.zhiku.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(JwtSettings.class)
public class SecurityConfiguration {
  private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

  @Bean
  SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder, ObjectMapper json) throws Exception {
    var errors = new ApiErrorResponses(json);
    return http
        // Stateless bearer tokens only: no cookie or session authentication exists, so CSRF does not apply.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers(HttpMethod.GET, "/api/status").permitAll()
            .requestMatchers("/api/**").authenticated()
            .anyRequest().denyAll())
        .oauth2ResourceServer(o -> o
            .jwt(j -> j.decoder(decoder))
            .authenticationEntryPoint(errors.unauthenticated())
            .accessDeniedHandler(errors.forbidden()))
        .exceptionHandling(e -> e
            .authenticationEntryPoint(errors.unauthenticated())
            .accessDeniedHandler(errors.forbidden()))
        .build();
  }

  @Bean
  JwtDecoder jwtDecoder(JwtSettings settings) {
    if (!settings.configured()) {
      log.warn("zhiku.auth.jwt-secret is missing or shorter than {} bytes; every bearer token will be rejected", JwtSettings.MIN_SECRET_BYTES);
      return token -> {
        throw new BadJwtException("JWT verification is not configured");
      };
    }
    var key = new SecretKeySpec(settings.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(settings.issuer()),
        new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
        new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(settings.audience()))));
    return decoder;
  }
}
