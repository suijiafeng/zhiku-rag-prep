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
 * 授权和版本效力在任何向量比较之前确定；只有已授权知识库中
 * 有效版本的 chunk 才能成为候选。精确检索；尚无改写、
 * 词法、融合或重排阶段，trace 会如实说明，而不是编造分数。
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
    // 效力和授权在（较慢的）embedding 调用之后读取，且查询会重新检查删除和撤回，
    // 因此请求进行中提交的变更不会被披露。之后的变更由调用方检查。
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
