package dev.zhiku.platform;

/** Contract error body. Messages are fixed text: no documents, SQL, credentials or stack traces. */
public record ApiError(String code, String message, String requestId) {}
