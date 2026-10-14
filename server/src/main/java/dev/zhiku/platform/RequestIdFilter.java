package dev.zhiku.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

/** 服务端生成的请求 id；从不信任客户端提供的 X-Request-Id。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Request-Id";
  private static final String ATTRIBUTE = RequestIdFilter.class.getName();

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, id);
    response.setHeader(HEADER, id);
    MDC.put("requestId", id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("requestId");
    }
  }

  public static String of(HttpServletRequest request) {
    return request.getAttribute(ATTRIBUTE) instanceof String id ? id : "unassigned";
  }

  /** 当前 servlet 请求的请求 id，请求之外为 "unassigned"。 */
  public static String current() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? of(a.getRequest()) : "unassigned";
  }
}
