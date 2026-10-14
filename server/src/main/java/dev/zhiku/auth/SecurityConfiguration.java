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
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
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
  SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder, ObjectMapper json,
      @Value("${zhiku.widget.allowed-origins:}") List<String> widgetOrigins) throws Exception {
    var errors = new ApiErrorResponses(json);
    var origins = widgetOrigins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
    if (!origins.isEmpty()) {
      // Only explicitly configured widget hosts, only the widget endpoints, never credentials.
      var cors = new CorsConfiguration();
      cors.setAllowedOrigins(origins);
      cors.setAllowedMethods(List.of("POST", "PUT"));
      cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
      cors.setExposedHeaders(List.of("X-Request-Id"));
      cors.setAllowCredentials(false);
      var source = new UrlBasedCorsConfigurationSource();
      source.registerCorsConfiguration("/api/chat", cors);
      source.registerCorsConfiguration("/api/messages/*/feedback", cors);
      http.cors(c -> c.configurationSource(source));
    }
    return http
        // Stateless bearer tokens only: no cookie or session authentication exists, so CSRF does not apply.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers(HttpMethod.GET, "/api/status").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/chat").authenticated()
            .requestMatchers(HttpMethod.PUT, "/api/messages/*/feedback").authenticated()
            // Widget tokens may only ask and rate their own answers.
            .requestMatchers("/api/**").access((auth, ctx) -> new AuthorizationDecision(auth.get() instanceof JwtAuthenticationToken t
                && t.isAuthenticated() && !JwtAccessScopeResolver.WIDGET.equals(t.getToken().getClaimAsString(JwtAccessScopeResolver.TOKEN_USE))))
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
  @ConditionalOnExpression("'${zhiku.auth.jwt-secret:}'.getBytes(T(java.nio.charset.StandardCharsets).UTF_8).length >= 32")
  JwtEncoder jwtEncoder(JwtSettings settings) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(settings.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
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
