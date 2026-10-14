package dev.zhiku.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.platform.ApiError;
import dev.zhiku.platform.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Writes the contract error shape for security failures. Messages are fixed constants:
 * they never echo the path, the principal, the token or whether a resource exists.
 */
final class ApiErrorResponses {
  private final ObjectMapper json;

  ApiErrorResponses(ObjectMapper json) {
    this.json = json;
  }

  AuthenticationEntryPoint unauthenticated() {
    return (request, response, exception) ->
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
  }

  AccessDeniedHandler forbidden() {
    return (request, response, exception) ->
        write(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Access denied");
  }

  private void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    json.writeValue(response.getWriter(), new ApiError(code, message, RequestIdFilter.of(request)));
  }
}
