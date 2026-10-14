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
  private final TraceRepository traces;

  public RetrievalService(KnowledgeBaseAccess access, DocumentService documents, RetrievalRepository repository,
      ObjectProvider<EmbeddingProvider> embeddings, RetrievalSettings settings, GrantLookup grants, TraceRepository traces) {
    this.traces = traces;
    this.grants = grants;
    this.access = access;
    this.documents = documents;
    this.repository = repository;
    this.embeddings = embeddings;
    this.settings = settings;
  }

  public Result search(UUID kbId, String question, Integer k, Instant at, KnowledgeBaseAction action) {
    var scope = access.require(kbId, action);
    return searchAuthorized(scope, kbId, question, k, at, "debug", UUID.randomUUID());
  }

  /** 问答、调试与评测共用的检索链路；每次都按 traceId 记录各阶段元数据，失败时也保留轨迹。 */
  public Result searchAuthorized(AccessScope scope, UUID kbId, String question, Integer k, Instant at, String origin, UUID traceId) {
    return searchAuthorized(scope, kbId, question, k, at, origin, traceId, 0);
  }

  public Result searchAuthorized(AccessScope scope, UUID kbId, String question, Integer k, Instant at, String origin, UUID traceId, int configVersion) {
    int limit = k == null ? settings.defaultK() : k;
    if (limit < 1 || limit > settings.maxK()) throw ApiException.unprocessable("k must be between 1 and " + settings.maxK());
    var provider = embeddings.getIfAvailable();
    traces.insert(traceId, scope, kbId, origin, at, limit, provider == null ? null : provider.model(), configVersion);
    var rewrite = Stage.skipped("rewrite", "查询改写", "未改写，保留原问题");
    var later = List.of(Stage.skipped("lexical", "词法召回", "未启用"), Stage.skipped("fusion", "融合排序", "未启用"),
        Stage.skipped("rerank", "重排", "未接入"));
    var vectorStarted = Instant.now();
    if (provider == null) {
      traces.addStages(traceId, stages(rewrite, Stage.timed("vector", "向量召回", "failed", vectorStarted, "未配置 embedding 模型"), later));
      throw ApiException.unavailable("Embedding model is not configured");
    }
    float[] query;
    try {
      var vectors = provider.embed(List.of(question));
      EmbeddingVectors.validate(vectors, 1);
      query = vectors.getFirst();
    } catch (RuntimeException e) {
      traces.addStages(traceId, stages(rewrite, Stage.timed("vector", "向量召回", "failed", vectorStarted, "向量化失败"), later));
      throw ApiException.unavailable("Embedding service is unavailable");
    }
    // 效力和授权在（较慢的）embedding 调用之后读取，且查询会重新检查删除和撤回，
    // 因此请求进行中提交的变更不会被披露。之后的变更由调用方检查。
    var eligible = documents.eligibleVersionIds(scope, kbId, at);
    var candidates = grants.grantedKnowledgeBases(scope.enterpriseId(), scope.userId()).contains(kbId)
        ? repository.nearest(eligible, provider.model(), query, limit) : List.<Retrieval.Candidate>of();
    var vector = Stage.timed("vector", "向量召回", "success", vectorStarted,
        "精确检索 · " + eligible.size() + " 个合格版本 · " + candidates.size() + " 条候选");
    var stages = stages(rewrite, vector, later);
    traces.setDimension(traceId, query.length);
    traces.addStages(traceId, stages);
    traces.addCandidates(traceId, candidates);
    return new Result(traceId, question, at, provider.model(), query.length, stages, candidates);
  }

  private static List<Stage> stages(Stage rewrite, Stage vector, List<Stage> later) {
    var all = new java.util.ArrayList<Stage>();
    all.add(rewrite);
    all.add(vector);
    all.addAll(later);
    return all;
  }

  /** 问答在检索之后追加"上下文与生成"阶段；轨迹不存在（检索前即结束）时不记录。 */
  public void recordGeneration(UUID traceId, Stage stage) {
    if (traceId != null && traces.exists(traceId)) traces.addStages(traceId, List.of(stage));
  }

  public void linkMessage(UUID traceId, UUID messageId) {
    traces.linkMessage(traceId, messageId);
  }

  public Retrieval.TraceView trace(UUID kbId, UUID traceId) {
    access.require(kbId, KnowledgeBaseAction.DEBUG_AND_EVALUATE);
    return traces.find(kbId, traceId).orElseThrow(ApiException::notFound);
  }

  /** 重建索引会整体替换切块：引用的切块已不存在时，答案不能再按这些引用披露。 */
  public boolean allChunksExist(List<UUID> chunkIds) {
    return chunkIds.isEmpty() || repository.allChunksExist(chunkIds);
  }
}
