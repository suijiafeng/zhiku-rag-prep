package dev.zhiku.auth;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class SecurityConfiguration {
 @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
  // No credential provider yet. All business paths fail closed, including unknown paths.
  return http.authorizeHttpRequests(a -> a.requestMatchers("/api/status").permitAll().anyRequest().denyAll())
   .build();
 }
}
