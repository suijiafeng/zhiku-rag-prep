package dev.zhiku.auth;

/** Outcome of a server-side authorization check. Only PERMITTED allows data access. */
public enum AuthorizationDecision {
  /** No trusted identity was established for the current request. */
  UNAUTHENTICATED,
  /** Identity exists but the target is not granted; identical for foreign and unknown targets. */
  DENIED,
  PERMITTED;

  public boolean permitted() {
    return this == PERMITTED;
  }
}
