package dev.zhiku.ingestion;

import static dev.zhiku.auth.KnowledgeBaseAction.MANAGE_DOCUMENTS;

import dev.zhiku.audit.AuditLog;
import dev.zhiku.document.DocumentRepository;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class IngestionService {
  private final KnowledgeBaseAccess access;
  private final DocumentRepository documents;
  private final IngestionRepository repository;
  private final IngestionSettings settings;
  private final AuditLog audit;

  public IngestionService(KnowledgeBaseAccess access, DocumentRepository documents, IngestionRepository repository,
      IngestionSettings settings, AuditLog audit) {
    this.access = access;
    this.documents = documents;
    this.repository = repository;
    this.settings = settings;
    this.audit = audit;
  }

  /** 在同一事务中保存文件和一个排队任务；解析稍后在 worker 中进行。 */
  @Transactional
  public UUID upload(UUID kbId, UUID documentId, UUID versionId, MultipartFile file) throws IOException {
    var scope = access.require(kbId, MANAGE_DOCUMENTS);
    var version = documents.findVersion(kbId, documentId, versionId).orElseThrow(ApiException::forbidden);
    if (version.withdrawn()) throw ApiException.conflict("Version is withdrawn");
    if (file.getSize() > settings.maxBytes()) {
      throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Upload exceeds the size limit");
    }
    var bytes = file.getBytes();
    var accepted = UploadValidator.validate(file.getOriginalFilename(), bytes);
    // 上面的校验可能耗时较长；期间已提交的删除必须优先（清理之后不再写入内容）。
    if (!documents.lockLiveDocument(documentId)) throw ApiException.forbidden();
    if (repository.hasBlob(versionId)) throw ApiException.conflict("Version content already uploaded; create a new version");
    repository.insertBlob(versionId, accepted, bytes, scope.userId());
    var jobId = repository.enqueue(versionId);
    audit.record(scope, "CONTENT_UPLOAD", "version:" + versionId, "OK",
        Map.of("mediaType", accepted.mediaType(), "bytes", bytes.length, "sha256", accepted.sha256()));
    return jobId;
  }

  @Transactional
  public void reindex(UUID kbId, UUID documentId, UUID versionId) {
    var scope = access.require(kbId, MANAGE_DOCUMENTS);
    documents.findVersion(kbId, documentId, versionId).orElseThrow(ApiException::forbidden);
    if (!documents.lockLiveDocument(documentId)) throw ApiException.forbidden();
    if (repository.requeue(versionId) != 1) throw ApiException.conflict("No finished ingestion job to re-run");
    audit.record(scope, "CONTENT_REINDEX", "version:" + versionId, "OK", Map.of());
  }
}
