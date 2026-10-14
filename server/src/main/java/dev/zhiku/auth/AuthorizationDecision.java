package dev.zhiku.auth;

/** 服务端授权检查的结果。只有 PERMITTED 允许访问数据。 */
public enum AuthorizationDecision {
  /** 当前请求未建立可信身份。 */
  UNAUTHENTICATED,
  /** 身份存在但目标未授权；对他人的目标和不存在的目标结果相同。 */
  DENIED,
  PERMITTED;

  public boolean permitted() {
    return this == PERMITTED;
  }
}
