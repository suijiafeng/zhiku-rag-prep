package dev.zhiku.auth;

import java.util.Optional;

/**
 * 当前请求身份的应用层端口。
 * 基础设施必须仅根据服务端认证构建范围；请求 DTO、
 * 浏览器选择的请求头或 widget 参数都不是可接受的来源。
 * 在 ADR-002 中的身份来源确认之前，不存在生产实现。
 */
@FunctionalInterface
public interface AccessScopeResolver {
  /** 不存在可信身份时为空。调用方必须将 null 视为空。 */
  Optional<AccessScope> currentScope();
}
