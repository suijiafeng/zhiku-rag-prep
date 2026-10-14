package dev.zhiku.platform;

/** 契约错误体。消息为固定文本：不含文档、SQL、凭据或堆栈跟踪。 */
public record ApiError(String code, String message, String requestId) {}
