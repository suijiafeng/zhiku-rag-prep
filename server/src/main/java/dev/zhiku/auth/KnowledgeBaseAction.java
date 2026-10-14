package dev.zhiku.auth;

/** Knowledge-base scoped operations from PRD v0.3 chapter 3. Each also requires the KB grant. */
public enum KnowledgeBaseAction {
  /** Question answering, citations and original-file download. */
  ASK_AND_CITE,
  /** Upload, publish, rebuild and delete documents. */
  MANAGE_DOCUMENTS,
  /** Retrieval debugging and evaluation. */
  DEBUG_AND_EVALUATE,
  /** Reading other users' question and answer bodies. */
  READ_OTHERS_CONVERSATIONS
}
