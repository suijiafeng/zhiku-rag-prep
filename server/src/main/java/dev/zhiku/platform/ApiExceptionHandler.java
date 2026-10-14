package dev.zhiku.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private static ResponseEntity<ApiError> body(HttpStatus status, String code, String message, HttpServletRequest req) {
    return ResponseEntity.status(status).body(new ApiError(code, message, RequestIdFilter.of(req)));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> api(ApiException e, HttpServletRequest req) {
    return body(e.status(), e.code(), e.getMessage(), req);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HandlerMethodValidationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    MissingServletRequestPartException.class
  })
  ResponseEntity<ApiError> invalid(Exception e, HttpServletRequest req) {
    return body(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION", "Request validation failed", req);
  }

  /** 先查后插的并发重复由唯一约束拦下：按冲突报告，不泄露约束名。 */
  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<ApiError> duplicate(DuplicateKeyException e, HttpServletRequest req) {
    return body(HttpStatus.CONFLICT, "ALREADY_EXISTS", "Already exists", req);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest req) {
    return body(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Upload exceeds the size limit", req);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiError> notFound(NoResourceFoundException e, HttpServletRequest req) {
    return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", req);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
    return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed", req);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest req) {
    log.error("Unhandled error, requestId={}", RequestIdFilter.of(req), e);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Internal error", req);
  }
}
