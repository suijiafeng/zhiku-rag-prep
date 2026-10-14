package dev.zhiku.auth;

import dev.zhiku.audit.AuditLog;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exchanges a user token for a short-lived widget token: one knowledge base, ask-only role, chat endpoint only.
 * The host page obtains it per session and must never persist it.
 */
@RestController
class WidgetTokenController {
  record WidgetTokenRequest(@NotNull UUID kbId) {}

  record WidgetToken(String token, Instant expiresAt) {}

  private final KnowledgeBaseAccess access;
  private final ObjectProvider<JwtEncoder> encoder;
  private final JwtSettings settings;
  private final Duration ttl;
  private final AuditLog audit;

  WidgetTokenController(KnowledgeBaseAccess access, ObjectProvider<JwtEncoder> encoder, JwtSettings settings,
      @Value("${zhiku.widget.token-ttl}") Duration ttl, AuditLog audit) {
    this.access = access;
    this.encoder = encoder;
    this.settings = settings;
    this.ttl = ttl;
    this.audit = audit;
  }

  @PostMapping("/api/widget-tokens")
  @ResponseStatus(HttpStatus.CREATED)
  WidgetToken issue(@Valid @RequestBody WidgetTokenRequest request) {
    if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t
        && JwtAccessScopeResolver.WIDGET.equals(t.getToken().getClaimAsString(JwtAccessScopeResolver.TOKEN_USE))) {
      throw ApiException.forbidden();
    }
    var scope = access.require(request.kbId(), KnowledgeBaseAction.ASK_AND_CITE);
    var jwtEncoder = encoder.getIfAvailable();
    if (jwtEncoder == null) throw ApiException.unavailable("Token signing is not configured");
    var now = Instant.now();
    var expires = now.plus(ttl);
    var claims = JwtClaimsSet.builder()
        .issuer(settings.issuer()).audience(List.of(settings.audience()))
        .subject(scope.userId().toString()).issuedAt(now).expiresAt(expires)
        .claim("enterprise_id", scope.enterpriseId().toString())
        .claim("roles", List.of(Role.EMPLOYEE.name()))
        .claim(JwtAccessScopeResolver.KB_SCOPE, List.of(request.kbId().toString()))
        .claim(JwtAccessScopeResolver.TOKEN_USE, JwtAccessScopeResolver.WIDGET)
        .build();
    var token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    audit.record(scope, "WIDGET_TOKEN_ISSUE", "kb:" + request.kbId(), "OK", Map.of("ttlSeconds", ttl.toSeconds()));
    return new WidgetToken(token, expires);
  }
}
