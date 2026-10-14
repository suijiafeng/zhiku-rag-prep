package dev.zhiku.chat;

import static dev.zhiku.auth.KnowledgeBaseAction.ASK_AND_CITE;

import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.knowledgebase.KnowledgeBaseService;
import dev.zhiku.platform.ApiException;
import dev.zhiku.platform.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class ChatController {
  private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> {
    var t = new Thread(r, "chat-heartbeat");
    t.setDaemon(true);
    return t;
  });

  record ChatRequest(@NotBlank @Size(max = 2000) String question, @NotNull UUID kbId, UUID conversationId, UUID clientRequestId) {}

  private final ChatService chat;
  private final KnowledgeBaseAccess access;
  private final KnowledgeBaseService knowledgeBases;
  private final TaskExecutor executor;
  private final ChatSettings settings;

  ChatController(ChatService chat, KnowledgeBaseAccess access, KnowledgeBaseService knowledgeBases,
      @org.springframework.beans.factory.annotation.Qualifier("chatExecutor") TaskExecutor executor, ChatSettings settings) {
    this.chat = chat;
    this.access = access;
    this.knowledgeBases = knowledgeBases;
    this.executor = executor;
    this.settings = settings;
  }

  /** 授权、会话归属和模型可用性的失败在流开始前以普通 JSON 返回。 */
  @PostMapping(path = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter ask(@Valid @RequestBody ChatRequest request, HttpServletRequest http) {
    var scope = access.require(request.kbId(), ASK_AND_CITE);
    chat.requireModel();
    if (request.conversationId() != null) chat.requireOwnConversation(scope, request.conversationId(), request.kbId());
    var question = request.question().strip();
    if (request.clientRequestId() != null) chat.accept(scope, request.clientRequestId(), request.kbId(), request.conversationId(), question);
    var requestId = RequestIdFilter.of(http);
    var emitter = new SseEmitter(settings.streamTimeout().toMillis());
    var closed = new AtomicBoolean(false);
    emitter.onCompletion(() -> closed.set(true));
    emitter.onTimeout(() -> closed.set(true));
    emitter.onError(e -> closed.set(true));
    var sink = new ChatSink() {
      @Override
      public void send(String event, Object data) throws IOException {
        if (closed.get()) throw new IOException("closed");
        emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
      }

      @Override
      public boolean cancelled() {
        return closed.get();
      }
    };
    // start 在请求线程上发送：提前发送的事件由 Spring 缓冲并在初始化时写出，工作线程之后才开始写响应。
    var traceId = UUID.randomUUID();
    var config = chat.effectiveConfig(request.kbId());
    var start = new java.util.LinkedHashMap<String, Object>();
    start.put("requestId", requestId);
    start.put("traceId", traceId.toString());
    start.put("configVersion", config.version());
    if (request.clientRequestId() != null) start.put("clientRequestId", request.clientRequestId().toString());
    try {
      emitter.send(SseEmitter.event().name("start").data(start, MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      closed.set(true);
    }
    long period = settings.heartbeatInterval().toMillis();
    var heartbeat = HEARTBEAT.scheduleAtFixedRate(() -> {
      if (closed.get()) return;
      try {
        emitter.send(SseEmitter.event().comment("keepalive"));
      } catch (IOException | IllegalStateException e) {
        closed.set(true);
      }
    }, period, period, TimeUnit.MILLISECONDS);
    try {
      executor.execute(() -> {
        try {
          chat.stream(scope, request.kbId(), question, request.conversationId(), request.clientRequestId(), sink, requestId, traceId, config);
        } finally {
          heartbeat.cancel(false);
          emitter.complete();
        }
      });
    } catch (RejectedExecutionException e) {
      heartbeat.cancel(false);
      if (request.clientRequestId() != null) chat.release(scope, request.clientRequestId());
      throw ApiException.unavailable("Too many concurrent answers");
    }
    return emitter;
  }

  /** 断开后查询调用方自己的请求，而不是再次提交问题。 */
  @GetMapping("/api/chat/requests/{clientRequestId}")
  ChatService.RequestView request(@PathVariable UUID clientRequestId) {
    return chat.request(knowledgeBases.requireScope(), clientRequestId);
  }

  @GetMapping("/api/conversations")
  List<ChatService.ConversationView> conversations() {
    return chat.conversations(knowledgeBases.requireScope());
  }

  @GetMapping("/api/conversations/{id}/messages")
  List<ChatService.MessageView> messages(@PathVariable UUID id) {
    return chat.messages(knowledgeBases.requireScope(), id);
  }
}
