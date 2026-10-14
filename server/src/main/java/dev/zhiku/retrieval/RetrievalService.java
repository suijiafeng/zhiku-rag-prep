package dev.zhiku.retrieval;

import dev.zhiku.auth.AccessScope;
import dev.zhiku.auth.GrantLookup;
import dev.zhiku.auth.KnowledgeBaseAction;
import dev.zhiku.document.DocumentService;
import dev.zhiku.embedding.EmbeddingProvider;
import dev.zhiku.embedding.EmbeddingVectors;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import dev.zhiku.retrieval.Retrieval.Result;
import dev.zhiku.retrieval.Retrieval.Stage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Authorization and version eligibility are resolved before any vector comparison; only chunks of
 * eligible versions inside a granted knowledge base can become candidates. Exact search; no rewrite,
 * lexical, fusion or rerank stage yet, and the trace says so instead of inventing scores.
 */
@Service
public class RetrievalService {
  private final KnowledgeBaseAccess access;
  private final DocumentService documents;
  private final RetrievalRepository repository;
  private final ObjectProvider<EmbeddingProvider> embeddings;
  private final RetrievalSettings settings;
  private final GrantLookup grants;

  public RetrievalService(KnowledgeBaseAccess access, DocumentService documents, RetrievalRepository repository,
      ObjectProvider<EmbeddingProvider> embeddings, RetrievalSettings settings, GrantLookup grants) {
    this.grants = grants;
    this.access = access;
    this.documents = documents;
    this.repository = repository;
    this.embeddings = embeddings;
    this.settings = settings;
  }

  public Result search(UUID kbId, String question, Integer k, Instant at, KnowledgeBaseAction action) {
    var scope = access.require(kbId, action);
    return searchAuthorized(scope, kbId, question, k, at);
  }

  public Result searchAuthorized(AccessScope scope, UUID kbId, String question, Integer k, Instant at) {
    int limit = k == null ? settings.defaultK() : k;
    if (limit < 1 || limit > settings.maxK()) throw ApiException.unprocessable("k must be between 1 and " + settings.maxK());
    var provider = embeddings.getIfAvailable();
    if (provider == null) throw ApiException.unavailable("Embedding model is not configured");

    long started = System.nanoTime();
    float[] query;
    try {
      var vectors = provider.embed(List.of(question));
      EmbeddingVectors.validate(vectors, 1);
      query = vectors.getFirst();
    } catch (RuntimeException e) {
      throw ApiException.unavailable("Embedding service is unavailable");
    }
    // Eligibility and grants are read after the (slow) embedding call, and the query re-checks deletion and withdrawal,
    // so changes committed while a request is in flight are not disclosed. Later changes are checked by callers.
    var eligible = documents.eligibleVersionIds(scope, kbId, at);
    var candidates = grants.grantedKnowledgeBases(scope.enterpriseId(), scope.userId()).contains(kbId)
        ? repository.nearest(eligible, provider.model(), query, limit) : List.<Retrieval.Candidate>of();
    long elapsed = (System.nanoTime() - started) / 1_000_000;

    var stages = List.of(
        new Stage("rewrite", "查询改写", "skipped", null, "未改写，保留原问题"),
        new Stage("vector", "向量召回", "success", elapsed, "精确检索 · " + eligible.size() + " 个合格版本 · " + candidates.size() + " 条候选"),
        new Stage("lexical", "词法召回", "skipped", null, "未启用"),
        new Stage("fusion", "融合排序", "skipped", null, "未启用"),
        new Stage("rerank", "重排", "skipped", null, "未接入"));
    return new Result(UUID.randomUUID(), question, at, provider.model(), query.length, stages, candidates);
  }

  /** 重建索引会整体替换切块：引用的切块已不存在时，答案不能再按这些引用披露。 */
  public boolean allChunksExist(List<UUID> chunkIds) {
    return chunkIds.isEmpty() || repository.allChunksExist(chunkIds);
  }
}
