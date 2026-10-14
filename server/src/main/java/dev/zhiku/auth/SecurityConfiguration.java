package dev.zhiku.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    // No credential provider yet. All business paths fail closed, including unknown paths.
    // Anonymous callers get 401, authenticated callers without a grant get 403; neither body
    // reveals whether the path or resource exists. CSRF stays at the framework default until
    // the session/token scheme in ADR-002 is decided.
    return http
        .authorizeHttpRequests(a -> a.requestMatchers("/api/status").permitAll().anyRequest().denyAll())
        .exceptionHandling(e -> e
            .authenticationEntryPoint(ApiErrorResponses.unauthenticated())
            .accessDeniedHandler(ApiErrorResponses.forbidden()))
        .build();
  }
}
