package dev.zhiku.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Writes the contract error shape {code, message, requestId} for security failures.
 * Messages are fixed constants: they never echo the path, the principal or whether a resource exists.
 */
final class ApiErrorResponses {
  static final String REQUEST_ID_HEADER = "X-Request-Id";

  private ApiErrorResponses() {}

  static AuthenticationEntryPoint unauthenticated() {
    return (request, response, exception) ->
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
  }

  static AccessDeniedHandler forbidden() {
    return (request, response, exception) ->
        write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Access denied");
  }

  private static void write(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    String requestId = UUID.randomUUID().toString();
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(REQUEST_ID_HEADER, requestId);
    response.getWriter().write(
        "{\"code\":\"%s\",\"message\":\"%s\",\"requestId\":\"%s\"}".formatted(code, message, requestId));
  }
}
