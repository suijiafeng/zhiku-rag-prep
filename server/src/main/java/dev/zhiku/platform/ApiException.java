package dev.zhiku.platform;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException unauthenticated() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
  }

  /** “未授权”和“不存在”返回相同响应，以免暴露隐藏资源。 */
  public static ApiException forbidden() {
    return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
  }

  public static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found");
  }

  public static ApiException unprocessable(String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION", message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
  }

  public static ApiException unavailable(String message) {
    return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", message);
  }
}
