package dev.zhiku.auth;

/** PRD v0.3 第 3 章中按知识库限定的操作。每项还需要该知识库的授权。 */
public enum KnowledgeBaseAction {
  /** 问答、引用和原文件下载。 */
  ASK_AND_CITE,
  /** 上传、发布、重建和删除文档。 */
  MANAGE_DOCUMENTS,
  /** 检索调试与评测。 */
  DEBUG_AND_EVALUATE,
  /** 读取其他用户的问题和回答正文。 */
  READ_OTHERS_CONVERSATIONS
}
